/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.integration;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.microsoft.jenkins.acs.integration.TestHelpers.loadProperty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public abstract class IntegrationTest {
    @ClassRule
    public static JenkinsRule jenkinsRule = new JenkinsRule() {
        {
            // otherwise we suffer from the default 180s timeout
            // this can also be override by the system property jenkins.test.timeout if we do not set it here.
            timeout = -1;
        }
    };

    @Rule
    public Timeout globalTimeout = new Timeout(30, TimeUnit.MINUTES);

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    private static final Logger LOGGER = Logger.getLogger(IntegrationTest.class.getName());

    protected Run run;
    protected FilePath workspace;
    protected Launcher launcher;
    protected TaskListener taskListener;

    protected EnvVars envVars = new EnvVars();

    protected TestEnvironment testEnv = null;

    protected String azureCredentialsId;
    protected String sshCredentialsId;
    protected String dockerCredentialsId;

    @Before
    public void setup() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(ACL.SYSTEM);

        workspace = new FilePath(tmpFolder.newFolder());

        run = mock(Run.class);
        launcher = mock(Launcher.class);

        taskListener = mock(TaskListener.class);
        when(taskListener.getLogger()).thenReturn(System.err);
        doAnswer(new Answer<PrintWriter>() {
            @Override
            public PrintWriter answer(InvocationOnMock invocationOnMock) throws Throwable {
                String msg = invocationOnMock.getArgument(0);
                System.err.print(msg);
                return new PrintWriter(System.err);
            }
        }).when(taskListener).error(any(String.class));
        when(run.getDisplayName()).thenReturn("acs-test-run");
        when(run.getEnvironment(any(TaskListener.class))).thenReturn(envVars);

        testEnv = new TestEnvironment();
    }

    protected void setupJenkinsCredentials(ACSRule acs) {
        AzureCredentials azureCredentials = new AzureCredentials(
                CredentialsScope.GLOBAL,
                azureCredentialsId = UUID.randomUUID().toString(),
                "Azure Credentials For ACS Test",
                acs.subscriptionId,
                acs.clientId,
                acs.clientSecret,
                acs.oauth2TokenEndpoint,
                acs.serviceManagementURL,
                acs.authenticationEndpoint,
                acs.resourceManagerEndpoint,
                acs.graphEndpoint);
        SystemCredentialsProvider.getInstance().getDomainCredentialsMap().get(Domain.global()).add(azureCredentials);

        BasicSSHUserPrivateKey sshCredentials = new BasicSSHUserPrivateKey(
                CredentialsScope.GLOBAL,
                sshCredentialsId = UUID.randomUUID().toString(),
                acs.adminUser,
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(acs.keyPair.privateKey),
                null,
                "Azure SSH Credentials For ACS Test");
        SystemCredentialsProvider.getInstance().getDomainCredentialsMap().get(Domain.global()).add(sshCredentials);

        if (StringUtils.isNotBlank(testEnv.dockerUsername)) {
            UsernamePasswordCredentials userPass = new UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    dockerCredentialsId = UUID.randomUUID().toString(),
                    "Docker Registry Credentials For ACS Test",
                    testEnv.dockerUsername,
                    testEnv.dockerPassword);
            SystemCredentialsProvider.getInstance().getDomainCredentialsMap().get(Domain.global()).add(userPass);
        }
    }

    protected static class TestEnvironment {
        public final String dockerRegistry;
        public final String dockerUsername;
        public final String dockerPassword;
        public final String dockerRepository;

        public final List<ResolvedDockerRegistryEndpoint> dockerCredentials;

        protected TestEnvironment() throws MalformedURLException {
            // this will be used for the private docker image pulling test
            // apart from the settings here, we need to prepare a private repository as well as an image
            // RUN:
            //     docker login $ACS_TEST_DOCKER_REGISTRY
            //     docker pull nginx
            //     docker tag nginx:latest $ACS_TEST_DOCKER_REPOSITORY/acs-test-private
            //     docker push $ACS_TEST_DOCKER_REPOSITORY/acs-test-private
            dockerRegistry = loadProperty("ACS_TEST_DOCKER_REGISTRY", "");
            dockerUsername = loadProperty("ACS_TEST_DOCKER_USERNAME", "");
            dockerPassword = loadProperty("ACS_TEST_DOCKER_PASSWORD", "");
            dockerRepository = loadProperty("ACS_TEST_DOCKER_REPOSITORY", "");

            dockerCredentials = new ArrayList<>();
        }
    }
}
