/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.jcraft.jsch.JSchException;
import com.microsoft.azure.management.containerservice.ContainerServiceOrchestratorTypes;
import com.microsoft.jenkins.acs.AzureACSPlugin;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.acs.util.JsonHelper;
import com.microsoft.jenkins.azurecommons.EnvironmentInjector;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsUtils;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import com.microsoft.jenkins.kubernetes.util.DockerConfigBuilder;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Item;
import hudson.model.TaskListener;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.lang.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.microsoft.jenkins.acs.util.DeployHelper.encodeURIPath;
import static com.microsoft.jenkins.acs.util.DeployHelper.escapeSingleQuote;

public class MarathonDeploymentCommand
        implements ICommand<MarathonDeploymentCommand.IMarathonDeploymentCommandData>, Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public void execute(IMarathonDeploymentCommandData context) {
        JobContext jobContext = context.getJobContext();
        final TaskListener taskListener = jobContext.getTaskListener();
        final String host = context.getMgmtFQDN();
        final SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        final FilePath workspace = jobContext.getWorkspace();
        final EnvVars envVars = context.getEnvVars();
        final String dockerCredentialsPath = context.getDcosDockerCredentialsPath();
        final boolean dcosDockerCredenditalsPathShared = context.isDcosDockerCredenditalsPathShared();
        final boolean enableSubstitution = context.isEnableConfigSubstitution();
        final String credentialsDirectoryName = nameForBuild(jobContext);
        final DeploymentConfig.Factory deploymentConfigFactory =
                new DeploymentConfig.Factory(context.getConfigFilePaths());
        final ContainerServiceOrchestratorTypes orchestratorType = context.getOrchestratorType();

        try {
            final List<ResolvedDockerRegistryEndpoint> dockerCredentials =
                    context.resolvedDockerRegistryEndpoints(jobContext.getRun().getParent());

            TaskResult taskResult = workspace.act(new MasterToSlaveCallable<TaskResult, Exception>() {
                private static final long serialVersionUID = 1L;

                @Override
                public TaskResult call() throws Exception {
                    TaskResult result = new TaskResult();
                    PrintStream logger = taskListener.getLogger();

                    DeploymentConfig config = deploymentConfigFactory.build(orchestratorType, workspace, envVars);

                    FilePath[] configPaths = config.getConfigFiles();
                    if (configPaths == null || configPaths.length == 0) {
                        logger.println(Messages.MarathonDeploymentCommand_configNotFound());
                        result.commandState = CommandState.HasError;
                        return result;
                    }

                    SSHClient client = new SSHClient(host, Constants.DCOS_SSH_PORT, sshCredentials).withLogger(logger);

                    try (SSHClient connected = client.connect()) {
                        Map<String, String> extraEnvVars =
                                copyCredentialsToAgents(
                                        connected,
                                        sshCredentials.getUsername(),
                                        workspace,
                                        dockerCredentialsPath,
                                        dcosDockerCredenditalsPathShared,
                                        credentialsDirectoryName,
                                        dockerCredentials,
                                        envVars,
                                        logger);
                        result.extraEnvVars.putAll(extraEnvVars);

                        for (FilePath configPath : configPaths) {
                            String deployedFilename = DeployHelper.generateRandomDeploymentFileName("json");
                            logger.println(Messages.MarathonDeploymentCommand_copyConfigFileTo(
                                    configPath.toURI(), connected.getHost(), deployedFilename));

                            ByteArrayInputStream in = DeployHelper.replaceMacro(
                                    configPath.read(), envVars, enableSubstitution);

                            connected.copyTo(in, deployedFilename);
                            in.reset();
                            String appId = JsonHelper.getMarathonAppId(in);
                            //ignore if app does not exist
                            logger.println(Messages.MarathonDeploymentCommand_deletingApp(appId));
                            connected.execRemote(String.format(
                                    "curl -i -X DELETE 'http://localhost/marathon/v2/apps/%s'",
                                    escapeSingleQuote(appId)));
                            logger.println(Messages.MarathonDeploymentCommand_deployingApp(deployedFilename, appId));
                            // NB. about "?force=true"
                            // Sometimes the deployment gets rejected after the previous delete of the same
                            // application ID with the following message:
                            //
                            // App is locked by one or more deployments. Override with the option '?force=true'.
                            // View details at '/v2/deployments/<DEPLOYMENT_ID>'.
                            connected.execRemote(String.format(
                                    "curl -i -H 'Content-Type: application/json' "
                                            + "-d@'%s' http://localhost/marathon/v2/apps?force=true",
                                    escapeSingleQuote(deployedFilename)));

                            logger.println(Messages.MarathonDeploymentCommand_removeTempFile(deployedFilename));

                            connected.execRemote(String.format("rm -f -- '%s'", escapeSingleQuote(deployedFilename)));

                            result.commandState = CommandState.Success;
                        }
                    }
                    return result;
                }
            });

            for (Map.Entry<String, String> entry : taskResult.extraEnvVars.entrySet()) {
                EnvironmentInjector.inject(jobContext.getRun(), envVars, entry.getKey(), entry.getValue());
            }

            String action = taskResult.commandState.isError() ? Constants.AI_DEPLOY_FAILED : Constants.AI_DEPLOYED;
            AzureACSPlugin.sendEventFor(action, Constants.AI_DCOS, jobContext.getRun(),
                    Constants.AI_FQDN, AppInsightsUtils.hash(host));

            context.setCommandState(taskResult.commandState);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            context.logError(e);
            AzureACSPlugin.sendEventFor(Constants.AI_DEPLOY_FAILED, Constants.AI_DCOS, jobContext.getRun(),
                    Constants.AI_FQDN, AppInsightsUtils.hash(host),
                    Constants.AI_MESSAGE, e.getMessage());
        }
    }

    @VisibleForTesting
    Map<String, String> copyCredentialsToAgents(
            SSHClient client,
            String linuxAdminUsername,
            FilePath workspace,
            String dockerCredentialsPath,
            boolean dcosDockerCredenditalsPathShared,
            String credentialsDirectoryName,
            List<ResolvedDockerRegistryEndpoint> dockerCredentials,
            EnvVars envVars,
            PrintStream logger) throws Exception {

        if (dockerCredentials.isEmpty()) {
            return ImmutableMap.of();
        }

        DockerConfigBuilder configBuilder = new DockerConfigBuilder(dockerCredentials);
        FilePath dockercfg = configBuilder.buildArchive(workspace);
        try {
            String remotePath = prepareCredentialsPath(
                    dockerCredentialsPath, credentialsDirectoryName, envVars, linuxAdminUsername);

            String dockerArchivePath = remotePath + "/" + Constants.MARATHON_DOCKER_CFG_ARCHIVE;

            List<String> agents = getAgentNodes(client);
            for (String agent : agents) {
                logger.println(Messages.MarathonDeploymentCommand_prepareDockerCredentialsFor(agent));

                // tunnel through the master SSH connection to connect to the agent SSH service
                SSHClient forwardClient =
                        client.forwardSSH(agent, Constants.DEFAULT_SSH_PORT).withLogger(logger);
                try (SSHClient connected = forwardClient.connect()) {
                    // prepare the remote directory structure
                    connected.execRemote(String.format("mkdir -p -- '%s'", escapeSingleQuote(remotePath)));

                    logger.println(Messages.MarathonDeploymentCommand_copyDockerCfgTo(
                            dockercfg.getRemote(), agent, dockerArchivePath));
                    connected.copyTo(dockercfg.read(), dockerArchivePath);

                    if (dcosDockerCredenditalsPathShared) {
                        logger.println(Messages.MarathonDeploymentCommand_skipAsPathShared());
                        break;
                    }
                }
            }

            if (!DeployHelper.checkURIForMarathon(dockerArchivePath)) {
                logger.println(Messages.MarathonDeploymentCommand_uriNotAccepted());
            }
            String archiveUri = "file://" + encodeURIPath(dockerArchivePath);
            // inject the environment variable
            logger.println(Messages.MarathonDeploymentCommand_injectEnvironmentVar(
                    Constants.MARATHON_DOCKER_CFG_ARCHIVE_URI, archiveUri));
            envVars.put(Constants.MARATHON_DOCKER_CFG_ARCHIVE_URI, archiveUri);
            return ImmutableMap.of(Constants.MARATHON_DOCKER_CFG_ARCHIVE_URI, archiveUri);
        } finally {
            dockercfg.delete();
        }
    }

    private static List<String> getAgentNodes(
            SSHClient client) throws Exception {
        final String command = "curl http://leader.mesos:1050/system/health/v1/nodes";
        String output = client.execRemote(command);

        List<String> hosts = getAgentNodes(output);
        if (hosts.isEmpty()) {
            throw new JSchException(Messages.MarathonDeploymentCommand_noAgentFound());
        }

        return hosts;
    }

    @VisibleForTesting
    static List<String> getAgentNodes(String json) {
        // sample input
        // {
        //     "nodes": [
        //         {"host_ip": "10.32.0.5",  "health": 0, "role": "agent"},
        //         {"host_ip": "10.0.0.4",   "health": 0, "role": "agent_public"},
        //         {"host_ip": "10.32.0.4",  "health": 0, "role": "agent"},
        //         {"host_ip": "172.16.0.5", "health": 0, "role": "master"},
        //         {"host_ip": "10.32.0.6",  "health": 0, "role": "agent"}
        //     ]
        // }
        final ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot parse JSON object from: " + json, e);
        }
        List<String> agentNodes = new ArrayList<>();
        JsonNode nodes = root.get("nodes");
        if (nodes == null || nodes.getNodeType() != JsonNodeType.ARRAY) {
            return agentNodes;
        }
        ArrayNode nodesArray = (ArrayNode) nodes;
        for (JsonNode node : nodesArray) {
            if (!"master".equals(node.get("role").textValue())) {
                agentNodes.add(node.get("host_ip").textValue());
            }
        }

        return agentNodes;
    }

    @VisibleForTesting
    static String prepareCredentialsPath(
            String dcosDockerCredentialsPath,
            String credentialsDirectoryName,
            EnvVars envVars,
            String linuxAdminUsername) {
        String name = StringUtils.trimToEmpty(envVars.expand(dcosDockerCredentialsPath));
        if (StringUtils.isNotBlank(name)) {
            if (name.charAt(0) != '/') {
                throw new IllegalArgumentException(
                        Messages.MarathonDeploymentCommand_relativePathNotAllowed(name));
            }
            name = name.replaceAll("/+$", "");
            if (name.isEmpty()) {
                name = "/";
            }
            return name;
        }
        return "/home/" + linuxAdminUsername + "/acs-plugin-dcos.docker/" + credentialsDirectoryName;
    }

    @VisibleForTesting
    static String nameForBuild(JobContext jobContext) {
        String runName = StringUtils.trimToEmpty(jobContext.getRun().getDisplayName());
        if (StringUtils.isBlank(runName)) {
            runName = UUID.randomUUID().toString();
        }
        return "acs-plugin-dcos-" + runName.replaceAll("[^0-9a-zA-Z]", "-").toLowerCase();
    }

    private static class TaskResult implements Serializable {
        private static final long serialVersionUID = 1L;

        private CommandState commandState = CommandState.Unknown;
        private final Map<String, String> extraEnvVars = new HashMap<>();
    }

    public interface IMarathonDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        SSHUserPrivateKey getSshCredentials();

        String getConfigFilePaths();

        ContainerServiceOrchestratorTypes getOrchestratorType();

        boolean isEnableConfigSubstitution();

        String getDcosDockerCredentialsPath();

        boolean isDcosDockerCredenditalsPathShared();

        List<ResolvedDockerRegistryEndpoint> resolvedDockerRegistryEndpoints(Item context) throws IOException;
    }
}
