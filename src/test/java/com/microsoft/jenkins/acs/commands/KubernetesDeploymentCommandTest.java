/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.jenkins.kubernetes.wrapper.KubernetesClientWrapper;
import io.kubernetes.client.openapi.ApiClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KubernetesDeploymentCommand}.
 */
public class KubernetesDeploymentCommandTest {
    @Test
    public void testGetMasterHost() {
        KubernetesDeploymentCommand.KubernetesDeployWorker worker =
                new KubernetesDeploymentCommand.KubernetesDeployWorker();

        assertEquals("Unknown", worker.getMasterHost(null));

        KubernetesClientWrapper wrapper = mock(KubernetesClientWrapper.class);
        assertEquals("Unknown", worker.getMasterHost(wrapper));

        ApiClient client = mock(ApiClient.class);
        when(wrapper.getClient()).thenReturn(client);
        assertNull(worker.getMasterHost(wrapper));

        when(client.getBasePath()).thenReturn("example.com");

        assertEquals("example.com", worker.getMasterHost(wrapper));
    }
}
