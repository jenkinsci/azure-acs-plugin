/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.resources.GenericResource;
import com.microsoft.azure.management.resources.fluentcore.arm.ResourceUtils;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.azurecommons.core.credentials.TokenCredentialData;
import hudson.FilePath;
import hudson.model.Item;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.io.OutputStream;

public class AKSDeploymentCommand
        extends KubernetesDeploymentCommandBase<AKSDeploymentCommand.IAKSDeploymentCommandData> {
    @Override
    public void execute(IAKSDeploymentCommandData context) {
        final Item owner = context.getJobContext().getOwner();
        final TokenCredentialData token = AzureHelper.getToken(owner, context.getAzureCredentialsId());

        AKSDeployWorker deployer = new AKSDeployWorker();
        deployer.setToken(token);
        deployer.setResourceGroupName(context.getResourceGroupName());
        deployer.setContainerServiceName(context.getContainerServiceName());

        doExecute(context, deployer);
    }

    static class AKSDeployWorker extends KubernetesDeployWorker {
        private TokenCredentialData token;
        private String resourceGroupName;
        private String containerServiceName;

        @Override
        protected FilePath[] resolveConfigFiles() throws IOException, InterruptedException {
            DeploymentConfig deploymentConfig = getConfigFactory().buildForAKS(getWorkspace(), getEnvVars());
            return deploymentConfig.getConfigFiles();
        }

        @Override
        protected void prepareKubeconfig(FilePath kubeconfigFile) throws Exception {
            Azure azureClient = AzureHelper.buildClient(token);
            String id = ResourceUtils.constructResourceId(
                    azureClient.subscriptionId(),
                    getResourceGroupName(),
                    Constants.AKS_PROVIDER,
                    "accessProfiles",
                    "clusterAdmin",
                    String.format("%s/%s", Constants.AKS_RESOURCE_TYPE, getContainerServiceName()));

            GenericResource resource = azureClient.genericResources().getById(id);
            Object properties = resource.properties();
            try {
                String userConfig = DeployHelper.getProperty(
                        properties, "kubeConfig", String.class);
                if (StringUtils.isBlank(userConfig)) {
                    throw new IllegalStateException("Null user kubeconfig returned from Azure");
                }
                byte[] kubeconfig = Base64.decodeBase64(userConfig);
                try (OutputStream out = kubeconfigFile.write()) {
                    out.write(kubeconfig);
                }
            } catch (IllegalArgumentException | ClassCastException e) {
                throw new IllegalStateException("Failed to get kubeconfig", e);
            }
        }

        public String getResourceGroupName() {
            return resourceGroupName;
        }

        public void setResourceGroupName(String resourceGroupName) {
            this.resourceGroupName = resourceGroupName;
        }

        public String getContainerServiceName() {
            return containerServiceName;
        }

        public void setContainerServiceName(String containerServiceName) {
            this.containerServiceName = containerServiceName;
        }

        public TokenCredentialData getToken() {
            return token;
        }

        public void setToken(TokenCredentialData token) {
            this.token = token;
        }
    }

    public interface IAKSDeploymentCommandData
            extends KubernetesDeploymentCommandBase.IKubernetesDeploymentCommandData {
        String getAzureCredentialsId();

        String getResourceGroupName();
    }
}
