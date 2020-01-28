/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.microsoft.azure.management.containerservice.ContainerServiceOrchestratorTypes;
import com.microsoft.jenkins.acs.AzureACSPlugin;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.EnvironmentInjector;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsUtils;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import com.microsoft.jenkins.kubernetes.wrapper.KubernetesClientWrapper;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.util.VariableResolver;
import io.kubernetes.client.openapi.ApiClient;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class KubernetesDeploymentCommandBase<
        T extends KubernetesDeploymentCommandBase.IKubernetesDeploymentCommandData>
        implements ICommand<T> {
    @Override
    public void execute(T context) {
        KubernetesDeployWorker worker = new KubernetesDeployWorker();
        doExecute(context, worker);
    }

    void doExecute(T context, KubernetesDeployWorker worker) {
        JobContext jobContext = context.getJobContext();
        final FilePath workspace = jobContext.getWorkspace();
        final EnvVars envVars = context.getEnvVars();
        final String managementFqdn = context.getMgmtFQDN();
        final String aiType = AzureACSPlugin.normalizeContainerSerivceType(context.getContainerServiceType());

        worker.setWorkspace(workspace);
        worker.setTaskListener(jobContext.getTaskListener());
        worker.setEnvVars(envVars);
        worker.setManagementFqdn(managementFqdn);
        worker.setSshCredentials(context.getSshCredentials());
        worker.setSecretNameCfg(context.getSecretName());
        worker.setDefaultSecretName(jobContext.getRun().getDisplayName());
        worker.setKubernetesNamespaceCfg(
                StringUtils.trimToNull(Util.replaceMacro(context.getSecretNamespace(), envVars)));
        worker.setEnableSubstitution(context.isEnableConfigSubstitution());
        worker.setConfigFactory(new DeploymentConfig.Factory(context.getConfigFilePaths()));
        worker.setOrchestratorType(context.getOrchestratorType());

        TaskResult taskResult;
        try {
            final List<ResolvedDockerRegistryEndpoint> registryCredentials =
                    context.resolvedDockerRegistryEndpoints(jobContext.getRun().getParent());

            worker.setRegistryCredentials(registryCredentials);

            taskResult = workspace.act(worker);

            for (Map.Entry<String, String> entry : taskResult.getExtraEnvVars().entrySet()) {
                EnvironmentInjector.inject(jobContext.getRun(), envVars, entry.getKey(), entry.getValue());
            }

            String action = taskResult.getCommandState().isError() ? Constants.AI_DEPLOY_FAILED : Constants.AI_DEPLOYED;
            AzureACSPlugin.sendEventFor(action, aiType, jobContext.getRun(),
                    Constants.AI_FQDN, AppInsightsUtils.hash(taskResult.getMasterHost()));

            context.setCommandState(taskResult.getCommandState());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            context.logError(e);
            AzureACSPlugin.sendEventFor(Constants.AI_DEPLOY_FAILED, aiType, jobContext.getRun(),
                    Constants.AI_FQDN, AppInsightsUtils.hash(managementFqdn),
                    Constants.AI_MESSAGE, e.getMessage());
        }
    }

    static class TaskResult implements Serializable {
        private static final long serialVersionUID = 1L;

        private CommandState commandState = CommandState.Unknown;
        private Map<String, String> extraEnvVars = new HashMap<>();
        private String masterHost;

        CommandState getCommandState() {
            return commandState;
        }

        void setCommandState(CommandState commandState) {
            this.commandState = commandState;
        }

        Map<String, String> getExtraEnvVars() {
            return extraEnvVars;
        }

        public void setExtraEnvVars(Map<String, String> extraEnvVars) {
            this.extraEnvVars = extraEnvVars;
        }

        String getMasterHost() {
            return masterHost;
        }

        void setMasterHost(String masterHost) {
            this.masterHost = masterHost;
        }
    }

    static class KubernetesDeployWorker extends MasterToSlaveCallable<TaskResult, Exception> {
        private static final long serialVersionUID = -1307651925021528450L;
        private TaskListener taskListener;
        private DeploymentConfig.Factory configFactory;
        private transient ContainerServiceOrchestratorTypes orchestratorType;
        private FilePath workspace;
        private EnvVars envVars;
        private String managementFqdn;
        private SSHUserPrivateKey sshCredentials;
        private List<ResolvedDockerRegistryEndpoint> registryCredentials;
        private String secretNameCfg;
        private String defaultSecretName;
        private String kubernetesNamespaceCfg;
        private boolean enableSubstitution;

        protected FilePath[] resolveConfigFiles() throws IOException, InterruptedException {
            DeploymentConfig deploymentConfig = configFactory.build(orchestratorType, workspace, envVars);
            return deploymentConfig.getConfigFiles();
        }

        protected void prepareKubeconfig(FilePath kubeconfigFile) throws Exception {
            SSHClient sshClient = new SSHClient(managementFqdn, Constants.KUBERNETES_SSH_PORT, sshCredentials)
                    .withLogger(taskListener.getLogger());
            try (SSHClient connected = sshClient.connect();
                 OutputStream out = kubeconfigFile.write()) {
                connected.copyFrom(Constants.KUBECONFIG_FILE, out);
            }
        }

        @Override
        public TaskResult call() throws Exception {
            TaskResult result = new TaskResult();
            PrintStream logger = taskListener.getLogger();

            FilePath[] configFiles = resolveConfigFiles();

            FilePath kubeconfigFile = workspace.createTempFile(Constants.KUBECONFIG_PREFIX, "");
            try {
                prepareKubeconfig(kubeconfigFile);

                KubernetesClientWrapper clientWrapper =
                        new KubernetesClientWrapper(kubeconfigFile.getRemote())
                                .withLogger(logger);

                if (!registryCredentials.isEmpty()) {
                    final String secretName = KubernetesClientWrapper.prepareSecretName(
                            secretNameCfg, defaultSecretName, envVars);

                    clientWrapper.createOrReplaceSecrets(
                            kubernetesNamespaceCfg, secretName, registryCredentials);

                    logger.println(Messages.KubernetesDeploymentCommand_injectSecretName(
                            Constants.KUBERNETES_SECRET_NAME_PROP, secretName));
                    envVars.put(Constants.KUBERNETES_SECRET_NAME_PROP, secretName);
                    result.getExtraEnvVars().put(Constants.KUBERNETES_SECRET_NAME_PROP, secretName);
                }

                if (enableSubstitution) {
                    clientWrapper.withVariableResolver(new VariableResolver.ByMap<>(envVars));
                }

                result.setMasterHost(getMasterHost(clientWrapper));
                clientWrapper.apply(configFiles);
                result.setCommandState(CommandState.Success);

                return result;
            } finally {
                logger.println(
                        Messages.KubernetesDeploymentCommand_deleteConfigFile(kubeconfigFile.getRemote()));
                try {
                    if (!kubeconfigFile.delete()) {
                        logger.println(Messages.KubernetesDeploymentCommand_failedToDeleteFile(
                                kubeconfigFile.getRemote()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.println(
                            Messages.KubernetesDeploymentCommand_failedToDeleteFile(e.getMessage()));
                } catch (Exception e) {
                    logger.println(
                            Messages.KubernetesDeploymentCommand_failedToDeleteFile(e.getMessage()));
                }
            }
        }

        String getMasterHost(KubernetesClientWrapper wrapper) {
            final String unknown = "Unknown";
            if (wrapper == null) {
                return unknown;
            }
            ApiClient client = wrapper.getClient();
            if (client == null) {
                return unknown;
            }
            return client.getBasePath();
        }

        public TaskListener getTaskListener() {
            return taskListener;
        }

        public void setTaskListener(TaskListener taskListener) {
            this.taskListener = taskListener;
        }

        public DeploymentConfig.Factory getConfigFactory() {
            return configFactory;
        }

        public void setConfigFactory(DeploymentConfig.Factory configFactory) {
            this.configFactory = configFactory;
        }

        public ContainerServiceOrchestratorTypes getOrchestratorType() {
            return orchestratorType;
        }

        public void setOrchestratorType(ContainerServiceOrchestratorTypes orchestratorType) {
            this.orchestratorType = orchestratorType;
        }

        public FilePath getWorkspace() {
            return workspace;
        }

        public void setWorkspace(FilePath workspace) {
            this.workspace = workspace;
        }

        public EnvVars getEnvVars() {
            return envVars;
        }

        public void setEnvVars(EnvVars envVars) {
            this.envVars = envVars;
        }

        public String getManagementFqdn() {
            return managementFqdn;
        }

        public void setManagementFqdn(String managementFqdn) {
            this.managementFqdn = managementFqdn;
        }

        public SSHUserPrivateKey getSshCredentials() {
            return sshCredentials;
        }

        public void setSshCredentials(SSHUserPrivateKey sshCredentials) {
            this.sshCredentials = sshCredentials;
        }

        public List<ResolvedDockerRegistryEndpoint> getRegistryCredentials() {
            return registryCredentials;
        }

        public void setRegistryCredentials(List<ResolvedDockerRegistryEndpoint> registryCredentials) {
            this.registryCredentials = registryCredentials;
        }

        public String getSecretNameCfg() {
            return secretNameCfg;
        }

        public void setSecretNameCfg(String secretNameCfg) {
            this.secretNameCfg = secretNameCfg;
        }

        public String getDefaultSecretName() {
            return defaultSecretName;
        }

        public void setDefaultSecretName(String defaultSecretName) {
            this.defaultSecretName = defaultSecretName;
        }

        public String getKubernetesNamespaceCfg() {
            return kubernetesNamespaceCfg;
        }

        public void setKubernetesNamespaceCfg(String kubernetesNamespaceCfg) {
            this.kubernetesNamespaceCfg = kubernetesNamespaceCfg;
        }

        public boolean isEnableSubstitution() {
            return enableSubstitution;
        }

        public void setEnableSubstitution(boolean enableSubstitution) {
            this.enableSubstitution = enableSubstitution;
        }
    }

    public interface IKubernetesDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        SSHUserPrivateKey getSshCredentials();

        String getSecretNamespace();

        String getConfigFilePaths();

        ContainerServiceOrchestratorTypes getOrchestratorType();

        String getContainerServiceType();

        String getContainerServiceName();

        boolean isEnableConfigSubstitution();

        String getSecretName();

        List<ResolvedDockerRegistryEndpoint> resolvedDockerRegistryEndpoints(Item context) throws IOException;
    }
}
