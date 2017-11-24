/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.util;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class Constants {
    public static final String PLUGIN_NAME = "AzureJenkinsACS";

    public static final String INVALID_OPTION = "*";

    public static final String KUBECONFIG_FILE = ".kube/config";

    public static final String KUBECONFIG_PREFIX = "kubeconfig-";

    public static final String DEFAULT_CHARSET = "UTF-8";

    /**
     * Increment step for the priority of the network security rule.
     */
    public static final int PRIORITY_STEP = 10;
    /**
     * Lowest priority for the network security rule (value range: [100, 4096]). Smaller number got higher priority.
     */
    public static final int LOWEST_PRIORITY = 4096;

    /**
     * URI scheme prefix (scheme://) pattern.
     * <p>
     * The scheme consists of a sequence of characters beginning with a letter and followed by any combination of
     * letters, digits, plus (+), period (.), or hyphen (-).
     */
    public static final Pattern URI_SCHEME_PREFIX =
            Pattern.compile("^[a-z][a-z0-9+.\\-]*://", Pattern.CASE_INSENSITIVE);

    /**
     * Length limit for the Kubernetes names.
     *
     * @see <a href="https://kubernetes.io/docs/concepts/overview/working-with-objects/names/">
     * Kubernetes Names
     * </a>
     */
    public static final int KUBERNETES_NAME_LENGTH_LIMIT = 253;

    /**
     * Pattern for the Kubernetes names.
     *
     * @see <a href="https://kubernetes.io/docs/concepts/overview/working-with-objects/names/">
     * Kubernetes Names
     * </a>
     */
    public static final Pattern KUBERNETES_NAME_PATTERN =
            Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$");

    public static final String KUBERNETES_SECRET_NAME_PREFIX = "acs-plugin-";
    public static final String KUBERNETES_SECRET_NAME_PROP = "KUBERNETES_SECRET_NAME";

    public static final String MARATHON_DOCKER_CFG_ARCHIVE = "docker.tar.gz";

    public static final String MARATHON_DOCKER_CFG_ARCHIVE_URI = "MARATHON_DOCKER_CFG_ARCHIVE_URI";

    public static final Set<ContainerServiceOchestratorTypes> SUPPORTED_ORCHESTRATOR = new HashSet<>(Arrays.asList(
            ContainerServiceOchestratorTypes.KUBERNETES,
            ContainerServiceOchestratorTypes.DCOS,
            ContainerServiceOchestratorTypes.SWARM
    ));

    public static final Set<String> SUPPORTED_ORCHESTRATOR_NAMES = new HashSet<>(Arrays.asList(
            ContainerServiceOchestratorTypes.KUBERNETES.toString(),
            ContainerServiceOchestratorTypes.DCOS.toString(),
            ContainerServiceOchestratorTypes.SWARM.toString()
    ));

    public static final String AKS = "AKS";
    public static final String AKS_PROVIDER = "Microsoft.ContainerService";
    public static final String AKS_RESOURCE_TYPE = "managedClusters";

    public static final int DEFAULT_SSH_PORT = 22;
    public static final int DCOS_SSH_PORT = 2200;
    public static final int KUBERNETES_SSH_PORT = 22;
    public static final int SWARM_SSH_PORT = 2200;

    /**
     * AI constants.
     */
    public static final String AI_ACS = "ACS";
    public static final String AI_SWARM = "Swarm";
    public static final String AI_KUBERNETES = "Kubernetes";
    public static final String AI_DCOS = "DCOS";
    public static final String AI_CUSTOM = "Custom";
    public static final String AI_AKS = "AKS";
    public static final String AI_RUN = "Run";
    public static final String AI_ORCHESTRATOR = "Orchestrator";
    public static final String AI_START_DEPLOY = "StartDeploy";
    public static final String AI_DEPLOYED = "Deployed";
    public static final String AI_DEPLOY_FAILED = "DeployFailed";
    public static final String AI_MESSAGE = "ErrorMessage";
    public static final String AI_FQDN = "FQDN";
    public static final String AI_RESOURCE_NAME = "ResourceName";

    public static int sshPort(ContainerServiceOchestratorTypes type) {
        switch (type) {
            case DCOS:
                return DCOS_SSH_PORT;
            case KUBERNETES:
                return KUBERNETES_SSH_PORT;
            case SWARM:
                return SWARM_SSH_PORT;
            default:
                return -1;
        }
    }

    private Constants() {
        // hide constructor
    }
}
