/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.integration;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.jenkins.acs.ACSDeploymentBuilder;
import com.microsoft.jenkins.acs.ACSDeploymentContext;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.KubernetesClientWrapper;
import hudson.model.Result;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.microsoft.jenkins.acs.integration.TestHelpers.loadFile;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class KubernetesDeploymentIT extends IntegrationTest {
    private static final Logger LOGGER = Logger.getLogger(KubernetesDeploymentIT.class.getName());

    /**
     * The rule to create the resource group and ACS cluster before the test and delete them afterwards.
     * <p>
     * It takes about 10 minutes for the ACS provision. So we would better provision the ACS for each test suite
     * using {@link ClassRule} rather than each test method ({@link org.junit.Rule}).
     */
    @ClassRule
    public static ACSRule acs = new ACSRule(ContainerServiceOchestratorTypes.KUBERNETES.toString());

    private ACSDeploymentContext deploymentContext;

    @Before
    @Override
    public void setup() throws Exception {
        super.setup();
        setupJenkinsCredentials(acs);

        deploymentContext = new ACSDeploymentContext(
                azureCredentialsId,
                acs.resourceGroup,
                acs.resourceName + " | Kubernetes",
                sshCredentialsId,
                "k8s/*.yml");
    }

    @Test
    public void combinedResourceDeployment() throws Exception {
        loadFile(KubernetesDeploymentIT.class, workspace, "k8s/combined-resource.yml");

        new ACSDeploymentBuilder(deploymentContext).perform(run, workspace, launcher, taskListener);

        verify(run, never()).setResult(Result.FAILURE);
    }

    @Test
    public void separeteResourceDeployment() throws Exception {
        loadFile(KubernetesDeploymentIT.class, workspace, "k8s/separated-deployment.yml");
        loadFile(KubernetesDeploymentIT.class, workspace, "k8s/separated-service.yml");

        new ACSDeploymentBuilder(deploymentContext).perform(run, workspace, launcher, taskListener);

        verify(run, never()).setResult(Result.FAILURE);
    }

    @Test
    public void testVariableSubstitute() throws Exception {
        loadFile(KubernetesDeploymentIT.class, workspace, "k8s/substitute.yml");

        deploymentContext.setEnableConfigSubstitution(true);
        envVars.put("DEPLOYMENT_NAME", "substitute-vars-deployment");
        envVars.put("LABEL_APP", "substitute-nginx");

        new ACSDeploymentBuilder(deploymentContext).perform(run, workspace, launcher, taskListener);

        verify(run, never()).setResult(Result.FAILURE);

        KubernetesClient client = buildKubernetesClient();
        Deployment deployment = client.extensions().deployments().inNamespace("default").withName("substitute-vars-deployment").get();
        Assert.assertNotNull(deployment);
        Assert.assertEquals("substitute-nginx", deployment.getMetadata().getLabels().get("app"));
    }

    /**
     * Tests for the image pulling from private repositories.
     * <p>
     * To run this test,
     * 1. prepare a private docker registry
     * 2. push an image named acs-test-private to the registry
     * 3. set the environment variables / system properties as advised in TestEnvironment.
     */
    @Test
    public void testDockerCredentials() throws Exception {
        if (StringUtils.isBlank(dockerCredentialsId)) {
            Assert.fail("No docker credentials configured in environment variables");
        }

        loadFile(KubernetesDeploymentIT.class, workspace, "k8s/private-repository.yml");

        deploymentContext.setEnableConfigSubstitution(true);
        envVars.put("ACS_TEST_DOCKER_REPOSITORY", testEnv.dockerRepository);
        envVars.put("IMAGE_NAME", "acs-test-private");

        DockerRegistryEndpoint endpoint = new DockerRegistryEndpoint(testEnv.dockerRegistry, dockerCredentialsId);
        deploymentContext.setContainerRegistryCredentials(Collections.singletonList(endpoint));

        KubernetesClient client = buildKubernetesClient();
        final CountDownLatch latch = new CountDownLatch(1);
        Watch deploymentWatch =
                client.extensions()
                        .deployments()
                        .inNamespace("default")
                        .withName("private-repository")
                        .watch(new Watcher<Deployment>() {
                            @Override
                            public void eventReceived(Action action, Deployment resource) {
                                Integer availableReplica = resource.getStatus().getAvailableReplicas();
                                if (availableReplica != null && availableReplica > 0) {
                                    latch.countDown();
                                }
                            }

                            @Override
                            public void onClose(KubernetesClientException cause) {
                                if (cause != null) {
                                    LOGGER.log(Level.SEVERE, null, cause);
                                }
                            }
                        });

        try {
            new ACSDeploymentBuilder(deploymentContext).perform(run, workspace, launcher, taskListener);

            verify(run, never()).setResult(Result.FAILURE);
            if (!latch.await(5, TimeUnit.MINUTES)) {
                Assert.fail("Timeout while waiting for the deployment to become available");
            }
        } finally {
            deploymentWatch.close();
        }
    }

    private KubernetesClient buildKubernetesClient() throws Exception {
        File config = tmpFolder.newFile();
        SSHClient client = new SSHClient(acs.containerService.masterFqdn(), Constants.KUBERNETES_SSH_PORT, acs.adminUser, null, acs.keyPair.privateKey);
        try (SSHClient connected = client.connect();
             OutputStream os = new FileOutputStream(config)) {
            connected.copyFrom(".kube/config", os);
        }
        return new KubernetesClientWrapper(config.getAbsolutePath()).getClient();
    }
}
