/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceOrchestratorTypes;
import com.microsoft.azure.management.resources.GenericResource;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.util.AzureBaseCredentials;
import com.microsoft.azure.util.AzureCredentialUtil;
import com.microsoft.jenkins.acs.commands.AKSDeploymentCommand;
import com.microsoft.jenkins.acs.commands.DeploymentChoiceCommand;
import com.microsoft.jenkins.acs.commands.EnablePortCommand;
import com.microsoft.jenkins.acs.commands.GetContainerServiceInfoCommand;
import com.microsoft.jenkins.acs.commands.KubernetesDeploymentCommand;
import com.microsoft.jenkins.acs.commands.MarathonDeploymentCommand;
import com.microsoft.jenkins.acs.commands.SwarmDeploymentCommand;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.BaseCommandContext;
import com.microsoft.jenkins.azurecommons.command.CommandService;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ACSDeploymentContext extends BaseCommandContext
        implements
        GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData,
        EnablePortCommand.IEnablePortCommandData,
        MarathonDeploymentCommand.IMarathonDeploymentCommandData,
        KubernetesDeploymentCommand.IKubernetesDeploymentCommandData,
        SwarmDeploymentCommand.ISwarmDeploymentCommandData,
        DeploymentChoiceCommand.IDeploymentChoiceCommandData,
        AKSDeploymentCommand.IAKSDeploymentCommandData {

    private final String azureCredentialsId;
    private final String resourceGroupName;
    private final String containerService;
    private final String sshCredentialsId;
    private final String configFilePaths;

    private boolean enableConfigSubstitution;
    private boolean swarmRemoveContainersFirst;

    private String secretNamespace;
    private String secretName;
    private String dcosDockerCredentialsPath;
    private boolean dcosDockerCredenditalsPathShared;
    private List<DockerRegistryEndpoint> containerRegistryCredentials;

    private transient String mgmtFQDN;
    private transient ContainerServiceOrchestratorTypes orchestratorType;
    private transient SSHUserPrivateKey sshCredentials;

    @DataBoundConstructor
    public ACSDeploymentContext(
            String azureCredentialsId,
            String resourceGroupName,
            String containerService,
            String sshCredentialsId,
            String configFilePaths) {
        this.azureCredentialsId = azureCredentialsId;
        this.resourceGroupName = StringUtils.trimToEmpty(resourceGroupName);
        this.containerService = StringUtils.trimToEmpty(containerService);
        this.sshCredentialsId = sshCredentialsId;
        this.configFilePaths = StringUtils.trimToEmpty(configFilePaths);
    }

    @Override
    public StepExecution startImpl(StepContext context) throws Exception {
        return new ExecutionImpl(new ACSDeploymentBuilder(this), context);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    private static final class ExecutionImpl extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;

        private final transient SimpleBuildStep delegate;

        ExecutionImpl(SimpleBuildStep delegate, StepContext context) {
            super(context);
            this.delegate = delegate;
        }

        @SuppressWarnings("ConstantConditions")
        @Override
        protected Void run() throws Exception {
            StepContext context = getContext();
            FilePath workspace = context.get(FilePath.class);
            workspace.mkdirs();
            delegate.perform(
                    context.get(Run.class),
                    workspace,
                    context.get(Launcher.class),
                    context.get(TaskListener.class));
            return null;
        }

        @Nonnull
        @Override
        public String getStatus() {
            String base = super.getStatus();
            if (delegate != null) {
                return delegate.getClass().getName() + ": " + base;
            } else {
                return base;
            }
        }
    }

    @Override
    public String getAzureCredentialsId() {
        return azureCredentialsId;
    }

    @Override
    public String getConfigFilePaths() {
        return this.configFilePaths;
    }

    public String getSshCredentialsId() {
        return sshCredentialsId;
    }

    @Override
    public SSHUserPrivateKey getSshCredentials() {
        if (sshCredentials == null) {
            sshCredentials = getSshCredentials(getSshCredentialsId());
        }
        return sshCredentials;
    }

    @Override
    public void setMgmtFQDN(String fqdn) {
        this.mgmtFQDN = fqdn;
    }

    @Override
    public String getResourceGroupName() {
        return this.resourceGroupName;
    }

    @Override
    public String getMgmtFQDN() {
        return this.mgmtFQDN;
    }

    @Override
    public IBaseCommandData getDataForCommand(ICommand command) {
        return this;
    }

    /**
     * We use the orchestratorType bound at configuration time. At build time we should check if this is consistent
     * with the configuration and fail if not.
     *
     * @return the container service's orchestrator type configured for the build
     */
    @Override
    public ContainerServiceOrchestratorTypes getOrchestratorType() {
        if (orchestratorType == null) {
            orchestratorType = ContainerServiceOrchestratorTypes.fromString(getOrchestratorType(containerService));
        }

        return orchestratorType;
    }

    @Override
    public String getContainerServiceType() {
        String type = getOrchestratorType(containerService);
        return type;
    }

    public String getContainerService() {
        return this.containerService;
    }


    /**
     * In order to pass the container service orchestrator type to the front-end, the {@link #containerService} field
     * will be in the following format. This will be stored into the configuration. At runtime, we need to extract
     * the container service name in order to pass it to the Azure service.
     * <p>
     * <code>
     * container_service_name|orchestrator_type
     * </code>
     *
     * @see #getContainerService()
     */
    @Override
    public String getContainerServiceName() {
        return getContainerServiceName(getContainerService());
    }

    @Override
    public String getSecretNamespace() {
        if (StringUtils.isBlank(secretNamespace)) {
            return getDescriptor().getDefaultSecretNamespace();
        }
        return secretNamespace;
    }

    @DataBoundSetter
    public void setSecretNamespace(String secretNamespace) {
        if (getDescriptor().getDefaultSecretNamespace().equals(secretNamespace)) {
            this.secretNamespace = null;
        } else {
            this.secretNamespace = StringUtils.trimToEmpty(secretNamespace);
        }
    }

    @Override
    public boolean isSwarmRemoveContainersFirst() {
        return this.swarmRemoveContainersFirst;
    }

    @DataBoundSetter
    public void setSwarmRemoveContainersFirst(boolean swarmRemoveContainersFirst) {
        this.swarmRemoveContainersFirst = swarmRemoveContainersFirst;
    }

    @Override
    public boolean isEnableConfigSubstitution() {
        return enableConfigSubstitution;
    }

    @DataBoundSetter
    public void setEnableConfigSubstitution(boolean enableConfigSubstitution) {
        this.enableConfigSubstitution = enableConfigSubstitution;
    }

    @Override
    public String getSecretName() {
        return secretName;
    }

    @DataBoundSetter
    public void setSecretName(String secretName) {
        this.secretName = StringUtils.trimToEmpty(secretName);
    }

    @Override
    public String getDcosDockerCredentialsPath() {
        return dcosDockerCredentialsPath;
    }

    @DataBoundSetter
    public void setDcosDockerCredentialsPath(String dcosDockerCredentialsPath) {
        this.dcosDockerCredentialsPath = StringUtils.trimToEmpty(dcosDockerCredentialsPath);
    }

    @Override
    public boolean isDcosDockerCredenditalsPathShared() {
        return dcosDockerCredenditalsPathShared;
    }

    @DataBoundSetter
    public void setDcosDockerCredenditalsPathShared(boolean dcosDockerCredenditalsPathShared) {
        this.dcosDockerCredenditalsPathShared = dcosDockerCredenditalsPathShared;
    }

    public List<DockerRegistryEndpoint> getContainerRegistryCredentials() {
        if (containerRegistryCredentials == null) {
            return new ArrayList<>(0);
        }
        return containerRegistryCredentials;
    }

    @DataBoundSetter
    public void setContainerRegistryCredentials(List<DockerRegistryEndpoint> containerRegistryCredentials) {
        List<DockerRegistryEndpoint> endpoints = new ArrayList<>();
        for (DockerRegistryEndpoint endpoint : containerRegistryCredentials) {
            String credentialsId = StringUtils.trimToNull(endpoint.getCredentialsId());
            if (credentialsId == null) {
                // no credentials item is selected, skip this endpoint
                continue;
            }

            String registryUrl = StringUtils.trimToNull(endpoint.getUrl());
            // null URL results in "https://index.docker.io/v1/" effectively
            if (registryUrl != null) {
                // It's common that the user omits the scheme prefix, we add http:// as default.
                // Otherwise it will cause MalformedURLException when we call endpoint.getEffectiveURL();
                if (!Constants.URI_SCHEME_PREFIX.matcher(registryUrl).find()) {
                    registryUrl = "http://" + registryUrl;
                }
            }
            endpoint = new DockerRegistryEndpoint(registryUrl, credentialsId);
            endpoints.add(endpoint);
        }
        this.containerRegistryCredentials = endpoints;
    }

    @Override
    public List<ResolvedDockerRegistryEndpoint> resolvedDockerRegistryEndpoints(Item context) throws IOException {
        List<ResolvedDockerRegistryEndpoint> endpoints = new ArrayList<>();
        List<DockerRegistryEndpoint> configured = getContainerRegistryCredentials();
        for (DockerRegistryEndpoint endpoint : configured) {
            DockerRegistryToken token = endpoint.getToken(context);
            if (token == null) {
                throw new IllegalArgumentException("No credentials found for " + endpoint);
            }
            endpoints.add(new ResolvedDockerRegistryEndpoint(endpoint.getEffectiveUrl(), token));
        }
        return endpoints;
    }

    public void configure(
            @Nonnull Run<?, ?> run,
            @Nonnull FilePath workspace,
            @Nonnull Launcher launcher,
            @Nonnull TaskListener listener) throws IOException, InterruptedException {
        CommandService commandService = CommandService.builder()
                .withTransition(GetContainerServiceInfoCommand.class, DeploymentChoiceCommand.class)
                .withSingleCommand(KubernetesDeploymentCommand.class)
                .withSingleCommand(AKSDeploymentCommand.class)
                .withTransition(MarathonDeploymentCommand.class, EnablePortCommand.class)
                .withTransition(SwarmDeploymentCommand.class, EnablePortCommand.class)
                .withStartCommand(GetContainerServiceInfoCommand.class)
                .build();

        final JobContext jobContext = new JobContext(run, workspace, launcher, listener);
        super.configure(jobContext, commandService);
    }

    private static SSHUserPrivateKey getSshCredentials(String id) {
        if (StringUtils.isBlank(id)) {
            // We don't need SSH credentials in AKS deployment
            return null;
        }
        SSHUserPrivateKey creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        BasicSSHUserPrivateKey.class,
                        Jenkins.getInstance(),
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(id));
        return creds;
    }

    public static String getContainerServiceName(String containerService) {
        if (StringUtils.isBlank(containerService)) {
            throw new IllegalArgumentException(Messages.ACSDeploymentContext_blankContainerService());
        }
        String[] part = containerService.split("\\|");
        return part[0].trim();
    }

    public static String getOrchestratorType(String containerService) {
        if (StringUtils.isBlank(containerService)) {
            throw new IllegalArgumentException(Messages.ACSDeploymentContext_blankContainerServiceType());
        }
        String[] parts = containerService.split("\\|");
        if (parts.length == 2) {
            String type = parts[1].trim();
            if (StringUtils.isNotEmpty(type)) {
                return type;
            }
        }
        throw new IllegalArgumentException(Messages.ACSDeploymentContext_blankContainerServiceType());
    }

    @VisibleForTesting
    interface CredentailsFinder {
        AzureBaseCredentials getCredential(String azureCredentialsId);

        SSHUserPrivateKey getSshCredentials(String sshCredentialsId);

        CredentailsFinder DEFAULT = new CredentailsFinder() {
            @Override
            public AzureBaseCredentials getCredential(String credentialsId) {
                return AzureCredentialUtil.getCredential2(credentialsId);
            }

            @Override
            public SSHUserPrivateKey getSshCredentials(String credentialsId) {
                return ACSDeploymentContext.getSshCredentials(credentialsId);
            }
        };
    }

    public static String validate(
            String azureCredentialsId,
            String resourceGroup,
            String containerService,
            String sshCredentialsId) {
        return validate(
                azureCredentialsId,
                resourceGroup,
                containerService,
                sshCredentialsId,
                CredentailsFinder.DEFAULT);
    }

    @VisibleForTesting
    static String validate(
            String azureCredentialsId,
            String resourceGroup,
            String containerService,
            String sshCredentialsId,
            CredentailsFinder credentailsFinder) {
        AzureBaseCredentials credential = credentailsFinder.getCredential(azureCredentialsId);

        if (credential == null) {
            return Messages.ACSDeploymentContext_missingCredentials();
        }
        if (StringUtils.isBlank(resourceGroup) || Constants.INVALID_OPTION.equals(resourceGroup)) {
            return Messages.ACSDeploymentContext_missingResourceGroup();
        }
        if (StringUtils.isBlank(containerService) || Constants.INVALID_OPTION.equals(containerService)) {
            return Messages.ACSDeploymentContext_missingContainerServiceName();
        }

        try {
            final String orchestratorTypeName = getOrchestratorType(containerService);
            if (Constants.AKS.equals(orchestratorTypeName)) {
                return null;
            }

            if (StringUtils.isBlank(sshCredentialsId)
                    || credentailsFinder.getSshCredentials(sshCredentialsId) == null) {
                return Messages.ACSDeploymentContext_missingSSHCredentials();
            }

            ContainerServiceOrchestratorTypes orchestratorType =
                    ContainerServiceOrchestratorTypes.fromString(orchestratorTypeName);

            if (!Constants.SUPPORTED_ORCHESTRATOR.contains(orchestratorType)) {
                return Messages.ACSDeploymentContext_orchestratorNotSupported(orchestratorTypeName);
            }
        } catch (IllegalArgumentException e) {
            return Messages.ACSDeploymentContext_validationError(e.getMessage());
        }
        return null;
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {
        public ListBoxModel doFillAzureCredentialsIdItems(@AncestorInPath Item owner) {
            StandardListBoxModel model = new StandardListBoxModel();
            model.add(Messages.ACSDeploymentContext_selectAzureCredentials(), Constants.INVALID_OPTION);
            model.includeAs(ACL.SYSTEM, owner, AzureBaseCredentials.class);
            return model;
        }

        public FormValidation doVerifyConfiguration(@AncestorInPath Item owner,
                                                    @QueryParameter String azureCredentialsId,
                                                    @QueryParameter String resourceGroupName,
                                                    @QueryParameter String containerService,
                                                    @QueryParameter String sshCredentialsId) {
            String validateResult = validate(
                    azureCredentialsId,
                    resourceGroupName,
                    containerService,
                    sshCredentialsId);
            if (validateResult != null) {
                return FormValidation.error(validateResult);
            }

            try {
                Azure azureClient = AzureHelper.buildClient(owner, azureCredentialsId);

                ResourceGroup group = azureClient.resourceGroups().getByName(resourceGroupName);
                if (group == null) {
                    return FormValidation.error(Messages.ACSDeploymentContext_resourceGroupNotFound());
                }

                String containerServiceType = getOrchestratorType(containerService);
                String containerServiceName = getContainerServiceName(containerService);
                if (Constants.AKS.equals(containerServiceType)) {
                    GenericResource resource =
                            azureClient.genericResources().get(
                                    resourceGroupName,
                                    Constants.AKS_PROVIDER,
                                    Constants.AKS_RESOURCE_TYPE,
                                    containerServiceName);
                    if (resource == null) {
                        return FormValidation.error(Messages.ACSDeploymentContext_containerServiceNotFound());
                    }
                    return FormValidation.ok(Messages.ACSDeploymentContext_validationSuccess());
                } else {
                    ContainerService container = azureClient
                            .containerServices()
                            .getByResourceGroup(resourceGroupName, containerServiceName);
                    if (container == null) {
                        return FormValidation.error(Messages.ACSDeploymentContext_containerServiceNotFound());
                    }

                    if (!container.orchestratorType().toString().equalsIgnoreCase(containerServiceType)) {
                        return FormValidation.error(Messages.ACSDeploymentContext_containerServiceTypeMissMatch());
                    }

                    try {
                        SSHClient sshClient = new SSHClient(
                                container.masterFqdn(),
                                Constants.sshPort(container.orchestratorType()),
                                getSshCredentials(sshCredentialsId));
                        try (SSHClient ignore = sshClient.connect()) {
                            sshClient.execRemote("ls");
                        }
                    } catch (Exception e) {
                        return FormValidation.error(Messages.ACSDeploymentContext_sshFailure(e.getMessage()));
                    }
                    return FormValidation.ok(Messages.ACSDeploymentContext_validationSuccess());
                }
            } catch (Exception e) {
                return FormValidation.error(Messages.ACSDeploymentContext_validationError(e.getMessage()));
            }
        }

        public ListBoxModel doFillResourceGroupNameItems(@AncestorInPath Item owner,
                                                         @QueryParameter String azureCredentialsId) {
            ListBoxModel model = new ListBoxModel();

            if (StringUtils.isBlank(azureCredentialsId)
                    || Constants.INVALID_OPTION.equals(azureCredentialsId)) {
                model.add(Messages.ACSDeploymentContext_selectAzureCredentialsFirst(), Constants.INVALID_OPTION);
                return model;
            }

            try {
                Azure azureClient = AzureHelper.buildClient(owner, azureCredentialsId);
                for (ResourceGroup resourceGroup : azureClient.resourceGroups().list()) {
                    model.add(resourceGroup.name());
                }
            } catch (Exception ex) {
                model.add(
                        Messages.ACSDeploymentContext_failedToLoadResourceGroups(ex.getMessage()),
                        Constants.INVALID_OPTION);
            }

            if (model.isEmpty()) {
                model.add(Messages.ACSDeploymentContext_noResourceGroupFound(), Constants.INVALID_OPTION);
            }

            return model;
        }

        public ListBoxModel doFillContainerServiceItems(@AncestorInPath Item owner,
                                                        @QueryParameter String azureCredentialsId,
                                                        @QueryParameter String resourceGroupName) {
            ListBoxModel model = new ListBoxModel();

            if (StringUtils.isBlank(azureCredentialsId)
                    || Constants.INVALID_OPTION.equals(azureCredentialsId)
                    || StringUtils.isBlank(resourceGroupName)
                    || Constants.INVALID_OPTION.equals(resourceGroupName)) {
                model.add(
                        Messages.ACSDeploymentContext_selectAzureCredentialsAndGroupFirst(),
                        Constants.INVALID_OPTION);
                return model;
            }

            try {
                Azure azureClient = AzureHelper.buildClient(owner, azureCredentialsId);

                PagedList<ContainerService> containerServices =
                        azureClient.containerServices().listByResourceGroup(resourceGroupName);
                for (ContainerService containerService : containerServices) {
                    ContainerServiceOrchestratorTypes orchestratorType = containerService.orchestratorType();
                    if (Constants.SUPPORTED_ORCHESTRATOR.contains(orchestratorType)) {
                        String value = String.format("%s | %s",
                                containerService.name(), containerService.orchestratorType());
                        model.add(value);
                    }
                }

                PagedList<GenericResource> resources =
                        azureClient.genericResources().listByResourceGroup(resourceGroupName);
                for (GenericResource resource : resources) {
                    if (Constants.AKS_PROVIDER.equals(resource.resourceProviderNamespace())
                            && Constants.AKS_RESOURCE_TYPE.equals(resource.resourceType())) {
                        String value = String.format("%s | %s",
                                resource.name(), Constants.AKS);
                        model.add(value);
                    }
                }
            } catch (Exception ex) {
                model.add(
                        Messages.ACSDeploymentContext_failedToLoadContainerServices(ex.getMessage()),
                        Constants.INVALID_OPTION);
            }

            if (model.isEmpty()) {
                model.add(Messages.ACSDeploymentContext_noContainerServiceFound(), Constants.INVALID_OPTION);
            }

            return model;
        }

        public FormValidation doCheckConfigFilePaths(@QueryParameter String value) {
            if (value == null || value.length() == 0) {
                return FormValidation.error(Messages.ACSDeploymentContext_configFilePathsRequired());
            }

            return FormValidation.ok();
        }

        public ListBoxModel doFillSshCredentialsIdItems(@AncestorInPath Item owner) {
            ListBoxModel m = new StandardListBoxModel().includeAs(ACL.SYSTEM, owner, SSHUserPrivateKey.class);
            return m;
        }

        public FormValidation doCheckSecretName(
                @QueryParameter String containerService,
                @QueryParameter String value) {
            String name = StringUtils.trimToEmpty(value);
            if (StringUtils.isEmpty(name)) {
                return FormValidation.ok();
            }

            ContainerServiceOrchestratorTypes orchestratorType;
            try {
                orchestratorType = ContainerServiceOrchestratorTypes.fromString(getOrchestratorType(containerService));
            } catch (IllegalArgumentException e) {
                // orchestrator type not determined, skip check
                return FormValidation.ok();
            }
            if (orchestratorType != ContainerServiceOrchestratorTypes.KUBERNETES) {
                return FormValidation.ok();
            }

            String variableStripped = DeployHelper.removeVariables(name);
            if (variableStripped.length() > Constants.KUBERNETES_NAME_LENGTH_LIMIT) {
                return FormValidation.error(Messages.ACSDeploymentContext_secretNameTooLong());
            }

            // Replace the variables with a single character valid name "a", and then check if it matches the pattern.
            // This will check if the static part of the secret name is valid at the configuration time.
            String variableReplaced = DeployHelper.replaceVariables(name, "a");
            if (Constants.KUBERNETES_NAME_PATTERN.matcher(variableReplaced).matches()) {
                return FormValidation.ok();
            }

            return FormValidation.error(Messages.ACSDeploymentContext_secretNameNotMatch(
                    Constants.KUBERNETES_NAME_PATTERN.pattern()));
        }

        public FormValidation doCheckDcosDockerCredentialsPath(
                @QueryParameter String containerService,
                @QueryParameter String value) {
            String path = StringUtils.trimToEmpty(value);
            if (StringUtils.isEmpty(path)) {
                return FormValidation.ok();
            }

            ContainerServiceOrchestratorTypes orchestratorType;
            try {
                orchestratorType = ContainerServiceOrchestratorTypes.fromString(getOrchestratorType(containerService));
            } catch (IllegalArgumentException e) {
                // orchestrator type not determined, skip check
                return FormValidation.ok();
            }
            if (orchestratorType != ContainerServiceOrchestratorTypes.DCOS) {
                return FormValidation.ok();
            }

            char first = path.charAt(0);
            if (first != '/' && first != '$') {
                return FormValidation.error(Messages.ACSDeploymentContext_onlyAbsolutePathAllowed());
            }

            String variableStripped = DeployHelper.removeVariables(path);
            if (StringUtils.isEmpty(variableStripped)) {
                // the path is constructed purely by variables, we cannot determine its value now.
                return FormValidation.ok();
            }
            if (!DeployHelper.checkURIForMarathon(variableStripped)) {
                // Return warnings instead error in case Marathon fixed this in future.
                return FormValidation.ok(Messages.ACSDeploymentContext_uriNotAccepted());
            }

            return FormValidation.ok();
        }

        public String getDefaultSecretNamespace() {
            return "default";
        }

        public String getDefaultRunOn() {
            return "Success";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(Run.class, FilePath.class, Launcher.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "acsDeploy";
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return Messages.plugin_displayName();
        }
    }
}
