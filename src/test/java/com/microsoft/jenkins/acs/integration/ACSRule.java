/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.integration;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.microsoft.azure.CloudException;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceMasterProfileCount;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.azure.management.compute.ContainerServiceVMSizeTypes;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.rules.MethodRule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.microsoft.jenkins.acs.integration.TestHelpers.generateRandomString;
import static com.microsoft.jenkins.acs.integration.TestHelpers.loadProperty;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ACSRule implements TestRule, MethodRule {
    private static final Logger LOGGER = Logger.getLogger(ACSRule.class.getName());

    protected Description testDescription;

    public final String subscriptionId;
    public final String clientId;
    public final String clientSecret;
    public final String oauth2TokenEndpoint;
    public final String serviceManagementURL;
    public final String authenticationEndpoint;
    public final String resourceManagerEndpoint;
    public final String graphEndpoint;

    public final String location;
    public final String resourceGroup;
    public final String resourceName;
    public final String containerServiceType;

    public final String adminUser;
    private final String privateKeyPath;

    private boolean useExisting;

    public AzureCredentials.ServicePrincipal servicePrincipal = null;
    public Azure azure = null;
    public KeyPair keyPair = null;
    public SSHUserPrivateKey sshCredentials = null;
    public ContainerService containerService = null;

    public ACSRule(String containerServiceType) {
        subscriptionId = loadProperty("ACS_TEST_SUBSCRIPTION_ID");
        clientId = loadProperty("ACS_TEST_CLIENT_ID");
        clientSecret = loadProperty("ACS_TEST_CLIENT_SECRET");
        oauth2TokenEndpoint = "https://login.windows.net/" + loadProperty("ACS_TEST_TENANT");

        serviceManagementURL = loadProperty("ACS_TEST_AZURE_MANAGEMENT_URL", "https://management.core.windows.net/");
        authenticationEndpoint = loadProperty("ACS_TEST_AZURE_AUTH_URL", "https://login.microsoftonline.com/");
        resourceManagerEndpoint = loadProperty("ACS_TEST_AZURE_RESOURCE_URL", "https://management.azure.com/");
        graphEndpoint = loadProperty("ACS_TEST_AZURE_GRAPH_URL", "https://graph.windows.net/");

        location = loadProperty("ACS_TEST_LOCATION", "SoutheastAsia");
        resourceGroup = loadProperty("ACS_TEST_RESOURCE_GROUP", "jenkins-acs-" + generateRandomString(6));
        // ACS_TEST_KUBERNETES_NAME
        // ACS_TEST_DCOS_NAME
        // ACS_TEST_SWARM_NAME
        resourceName = loadProperty("ACS_TEST_" + containerServiceType.toUpperCase() + "_NAME", "jenkins-acs-" + generateRandomString(6));
        this.containerServiceType = containerServiceType;

        useExisting = Boolean.parseBoolean(loadProperty("ACS_TEST_USE_EXISTING", "true"));
        adminUser = loadProperty("ACS_TEST_ADMIN_USER", "azureuser");
        privateKeyPath = loadProperty("ACS_TEST_PRIVATE_KEY_PATH", new File(System.getProperty("user.home"), ".ssh/id_rsa").getAbsolutePath());
    }

    public void before() throws Exception {
        LOGGER.log(Level.INFO, "Setting up test cluster environment for {0} in resource group {1} with name {2}",
                new Object[]{containerServiceType, resourceGroup, resourceName});
        servicePrincipal = new AzureCredentials.ServicePrincipal(
                subscriptionId,
                clientId,
                clientSecret,
                oauth2TokenEndpoint,
                serviceManagementURL,
                authenticationEndpoint,
                resourceManagerEndpoint,
                graphEndpoint);
        azure = AzureHelper.buildClientFromServicePrincipal(servicePrincipal);

        if (useExisting) {
            loadExisting();
        } else {
            provision();
        }
    }

    private void loadExisting() throws Exception {
        String privateKey = FileUtils.readFileToString(new File(privateKeyPath));
        sshCredentials = buildSSHKey(adminUser, privateKey);
        keyPair = new KeyPair(privateKey);

        LOGGER.log(Level.INFO, "=== Loading container service in resource group {0} with name {1}",
                new Object[]{resourceGroup, resourceName});
        containerService = azure.containerServices().getByResourceGroup(resourceGroup, resourceName);
        Assert.assertNotNull(String.format("Container service %s/%s was not found", resourceGroup, resourceName), containerService);
        LOGGER.log(Level.INFO, "=== Loaded container service with master {0}", containerService.masterFqdn());
    }

    private SSHUserPrivateKey buildSSHKey(String user, String privateKey) {
        SSHUserPrivateKey key = mock(SSHUserPrivateKey.class);
        when(key.getUsername()).thenReturn(user);
        when(key.getPrivateKeys()).thenReturn(Collections.singletonList(privateKey));
        when(key.getPassphrase()).thenReturn(null);
        return key;
    }

    /**
     * Provision the ACS instance for the tests in the rule's scope.
     *
     * Probably not a good idea to provision the ACS for each test classes as it takes a very long time for the
     * provision process. Besides, the success return of the creation method doesn't imply the underlying container
     * service is ready for work. We may suffer from connection issue shortly after the creation.
     *
     * The clean up process, when work in a successful build, clean up all the resource as expected; but when it fails,
     * all the context of the failure will be cleared too, which is not good for diagnostic.
     *
     * Leave it here for future reference.
     */
    private void provision() throws Exception {
        keyPair = new KeyPair();
        LOGGER.log(Level.INFO, "SSH Key used for test container service creation: \npublic key:\n{0}\nprivate key:\n{1}",
                new Object[]{keyPair.publicKey, keyPair.privateKey});
        sshCredentials = buildSSHKey(adminUser, keyPair.privateKey);

        LOGGER.log(Level.INFO, "=== Creating resource group {0} in location {1}", new Object[]{resourceGroup, location});
        ResourceGroup rg = azure.resourceGroups()
                .define(resourceGroup)
                .withRegion(location)
                .create();
        Assert.assertNotNull(rg);

        LOGGER.log(Level.INFO, "=== Starting provision of ACS {0} in resource group {1} with name {2}",
                new Object[]{containerServiceType, resourceGroup, resourceName});
        ContainerService.DefinitionStages.WithOrchestrator withOrchestrator =
                azure.containerServices()
                        .define(resourceName)
                        .withRegion(location)
                        .withExistingResourceGroup(resourceGroup);
        ContainerServiceOchestratorTypes type = ContainerServiceOchestratorTypes.fromString(containerServiceType);
        ContainerService.DefinitionStages.WithLinux withLinux = null;
        if (type == ContainerServiceOchestratorTypes.KUBERNETES) {
            withLinux =
                    withOrchestrator.withKubernetesOrchestration()
                            .withServicePrincipal(servicePrincipal.getClientId(), servicePrincipal.getClientSecret());
        } else if (type == ContainerServiceOchestratorTypes.DCOS) {
            withLinux =
                    withOrchestrator.withDcosOrchestration()
                            .withDiagnostics();
        } else if (type == ContainerServiceOchestratorTypes.SWARM) {
            withLinux =
                    withOrchestrator.withDcosOrchestration();
        } else {
            Assert.fail("Container service type '" + containerServiceType + "' is not supported");
        }

        containerService = withLinux
                .withLinux()
                .withRootUsername(adminUser)
                .withSshKey(keyPair.publicKey)
                .withMasterNodeCount(ContainerServiceMasterProfileCount.MIN)
                .withMasterLeafDomainLabel(resourceName)
                .defineAgentPool("pool")
                .withVMCount(1)
                .withVMSize(ContainerServiceVMSizeTypes.STANDARD_A1)
                .withLeafDomainLabel(resourceName + "-agent")
                .attach()
                .create();
        Assert.assertNotNull(containerService);
        LOGGER.log(Level.INFO, "=== Created container service with master {0}", containerService.masterFqdn());

        SSHClient client = null;
        if (type == ContainerServiceOchestratorTypes.KUBERNETES) {
            client = new SSHClient(containerService.masterFqdn(), Constants.KUBERNETES_SSH_PORT, adminUser, null, keyPair.privateKey);
        } else if (type == ContainerServiceOchestratorTypes.DCOS) {
            client = new SSHClient(containerService.masterFqdn(), Constants.DCOS_SSH_PORT, adminUser, null, keyPair.privateKey);
        } else if (type == ContainerServiceOchestratorTypes.SWARM) {
            client = new SSHClient(containerService.masterFqdn(), Constants.SWARM_SSH_PORT, adminUser, null, keyPair.privateKey);
        } else if (type == ContainerServiceOchestratorTypes.CUSTOM) {
            // ignore
        }
        if (client != null) {
            // it takes some time before the master node becomes available
            LOGGER.log(Level.INFO, "=== Trying to connect to the master node to ensure its availability");
            for (int i = 1; i <= 20; ++i) {
                try {
                    try (SSHClient ignore = client.connect()) {
                        LOGGER.log(Level.INFO, i + " - Master node " + containerService.masterFqdn() + " is available now");
                        break;
                    }
                } catch (JSchException ex) {
                    LOGGER.log(Level.INFO, i + " - Error occurred while connecting to " + containerService.masterFqdn(), ex);
                    // sleep 5 seconds before next retry
                    Thread.sleep(5000);
                }
            }
        }
    }

    public void after() throws Exception {
        if (useExisting) {
            return;
        }
        try {
            if (azure != null) {
                LOGGER.log(Level.INFO, "=== Deleting resource group {0}", resourceGroup);
                azure.resourceGroups().deleteByName(resourceGroup);
            }
        } catch (CloudException e) {
            if (e.response().code() != 404) {
                LOGGER.log(Level.SEVERE, null, e);
            }
        }
    }

    public ACSRule withExisting() {
        useExisting = true;
        return this;
    }

    @Override
    public Statement apply(Statement base, FrameworkMethod method, Object target) {
        return apply(base, Description.createTestDescription(method.getMethod().getDeclaringClass(), method.getName(), method.getAnnotations()));
    }

    @Override
    public Statement apply(final Statement base, final Description description) {
        if (description.getAnnotation(WithoutACS.class) != null) {
            // request has been made to not create the instance for this test method
            return base;
        }

        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                testDescription = description;
                before();
                try {
                    base.evaluate();
                } finally {
                    after();
                    testDescription = null;
                }
            }
        };
    }

    public static class KeyPair {
        public String publicKey;
        public String privateKey;

        public KeyPair() throws JSchException {
            com.jcraft.jsch.KeyPair kpair = com.jcraft.jsch.KeyPair.genKeyPair(new JSch(), com.jcraft.jsch.KeyPair.RSA);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            kpair.writePrivateKey(out);
            privateKey = out.toString();
            out.reset();
            kpair.writePublicKey(out, "acs-test");
            publicKey = out.toString();
        }

        public KeyPair(String privateKey) {
            this.privateKey = privateKey;
        }
    }
}
