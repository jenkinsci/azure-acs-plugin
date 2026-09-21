/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.Run;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.microsoft.jenkins.acs.commands.MarathonDeploymentCommand.nameForBuild;
import static com.microsoft.jenkins.acs.commands.MarathonDeploymentCommand.prepareCredentialsPath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link MarathonDeploymentCommand}.
 */
public class MarathonDeploymentCommandTest {
    private static final String USER = "azureuser";
    private static final FilePath WORKSPACE = new FilePath(new File(System.getProperty("java.io.tmpdir")));

    private MarathonDeploymentCommand command;
    private SSHClient master;
    private SSHClient slave;

    private ResolvedDockerRegistryEndpoint[] endpoints;

    @Before
    public void setup() throws Exception {
        command = new MarathonDeploymentCommand();

        endpoints = new ResolvedDockerRegistryEndpoint[]{
                new ResolvedDockerRegistryEndpoint(
                        new URL("https://index.docker.io/v1/"),
                        new DockerRegistryToken("user", "dXNlcjpwYXNzd29yZA==")),
                new ResolvedDockerRegistryEndpoint(
                        new URL("http://acr.azurecr.io"),
                        new DockerRegistryToken("anotherUser", "YW5vdGhlclVzZXI6aGFoYWhh"))
        };
    }

    @Test
    public void testCopyCredentialsToAgentsEmpty() throws Exception {
        Map<String, String> results = command.copyCredentialsToAgents(
                null, null, null, null, false, null, new ArrayList<ResolvedDockerRegistryEndpoint>(0), null, null);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testCopyCredentialsToAgentsSuccess() throws Exception {
        prepareSSHClient();
        final boolean shared = false;
        Map<String, String> results = command.copyCredentialsToAgents(
                master, USER, WORKSPACE, "/creds", shared, "some-name", Arrays.asList(endpoints), new EnvVars(), System.out);

        verify(slave, times(4)).copyTo(any(InputStream.class), any(String.class));
        assertEquals("file:///creds/docker.tar.gz", results.get(Constants.MARATHON_DOCKER_CFG_ARCHIVE_URI));
    }

    @Test
    public void testCopyCredentialsToAgentsShared() throws Exception {
        prepareSSHClient();
        final boolean shared = true;
        Map<String, String> results = command.copyCredentialsToAgents(
                master, USER, WORKSPACE, "/shared", shared, "some-name", Arrays.asList(endpoints), new EnvVars(), System.out);

        verify(slave, times(1)).copyTo(any(InputStream.class), any(String.class));
        assertEquals("file:///shared/docker.tar.gz", results.get(Constants.MARATHON_DOCKER_CFG_ARCHIVE_URI));
    }

    @Test
    public void testGetAgentNodesFromString() throws Exception {
        String nodesString = IOUtils.toString(MarathonDeploymentCommand.class.getResourceAsStream("nodes.json"));
        List<String> nodes = MarathonDeploymentCommand.getAgentNodes(nodesString);
        assertEquals(Arrays.asList("10.0.0.4", "10.32.0.6", "10.32.0.8", "10.32.0.4"), nodes);
    }

    @Test
    public void testPrepareCredentialsPath() {
        EnvVars empty = new EnvVars();
        EnvVars one = new EnvVars("one", "Azure");
        String user = "azureuser";

        try {
            prepareCredentialsPath("relative/path", "", empty, user);
            fail("Should not allow relative path");
        } catch (IllegalArgumentException e) {
            // expected
        }

        assertEquals("/path/1", prepareCredentialsPath("/path/1", "o", empty, user));
        assertEquals("/path/2", prepareCredentialsPath("/path/2/", "o", empty, user));
        assertEquals("/", prepareCredentialsPath("////", "o", empty, user));
        assertEquals("/path/Azure", prepareCredentialsPath("/path/$one", "o", one, user));
        assertEquals("/home/azureuser/acs-plugin-dcos.docker/test-dir", prepareCredentialsPath(null, "test-dir", one, user));
    }

    @Test
    public void testNameForBuild() {
        assertEquals("acs-plugin-dcos-def", nameForBuild(jobContext("abc", "def")));
        assertEquals("acs-plugin-dcos-def", nameForBuild(jobContext("", "def")));
        assertEquals("acs-plugin-dcos-de-", nameForBuild(jobContext("a.c", "de/")));
        String name = nameForBuild(jobContext("", ""));
        String prefix = "acs-plugin-dcos-";
        assertTrue(name.length() > prefix.length());
        UUID.fromString(name.substring(prefix.length()));
    }

    private JobContext jobContext(String jobName, String runName) {
        JobContext context = mock(JobContext.class);
        Run run = mock(Run.class);
        Job job = mock(Job.class);
        when(context.getRun()).thenReturn(run);
        when(run.getParent()).thenReturn(job);
        when(run.getDisplayName()).thenReturn(runName);
        when(job.getName()).thenReturn(jobName);
        return context;
    }

    private void prepareSSHClient() throws Exception {
        master = mock(SSHClient.class);
        String nodes = IOUtils.toString(MarathonDeploymentCommand.class.getResourceAsStream("nodes.json"));
        when(master.execRemote("curl http://leader.mesos:1050/system/health/v1/nodes")).thenReturn(nodes);
        slave = mock(SSHClient.class);
        when(master.forwardSSH(any(String.class), any(Integer.TYPE))).thenReturn(slave);
        when(slave.withLogger(any(PrintStream.class))).thenReturn(slave);
        when(slave.connect()).thenReturn(slave);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                InputStream in = invocation.getArgument(0);
                in.close();
                return null;
            }
        }).when(slave).copyTo(any(InputStream.class), any(String.class));
    }
}
