/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.google.common.annotations.VisibleForTesting;
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerServiceOrchestratorTypes;
import com.microsoft.azure.management.network.LoadBalancer;
import com.microsoft.azure.management.network.LoadBalancerBackend;
import com.microsoft.azure.management.network.LoadBalancerFrontend;
import com.microsoft.azure.management.network.LoadBalancingRule;
import com.microsoft.azure.management.network.LoadDistribution;
import com.microsoft.azure.management.network.NetworkSecurityGroup;
import com.microsoft.azure.management.network.NetworkSecurityRule;
import com.microsoft.azure.management.network.SecurityRuleAccess;
import com.microsoft.azure.management.network.SecurityRuleDirection;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.orchestrators.ServicePort;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.core.credentials.TokenCredentialData;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Item;
import hudson.model.TaskListener;
import jenkins.security.MasterToSlaveCallable;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class EnablePortCommand implements ICommand<EnablePortCommand.IEnablePortCommandData>, Serializable {
    private static final long serialVersionUID = 1L;

    public static final int LOAD_BALANCER_IDLE_TIMEOUT_IN_MINUTES = 5;

    static final class InvalidConfigException extends Exception {
        InvalidConfigException(String message) {
            super(message);
        }
    }

    @Override
    public void execute(IEnablePortCommandData context) {
        JobContext jobContext = context.getJobContext();
        final Item owner = jobContext.getOwner();
        final FilePath workspace = jobContext.getWorkspace();
        final TaskListener taskListener = jobContext.getTaskListener();
        final EnvVars envVars = context.getEnvVars();
        final DeploymentConfig.Factory configFactory = new DeploymentConfig.Factory(context.getConfigFilePaths());
        final ContainerServiceOrchestratorTypes orchestratorType = context.getOrchestratorType();
        final String azureCredentialsId = context.getAzureCredentialsId();
        final String resourceGroupName = context.getResourceGroupName();

        try {
            final TokenCredentialData token = AzureHelper.getToken(owner, azureCredentialsId);
            CommandState state = workspace.act(new MasterToSlaveCallable<CommandState, Exception>() {
                @Override
                public CommandState call() throws Exception {
                    PrintStream logger = taskListener.getLogger();

                    Azure azureClient = AzureHelper.buildClient(token);

                    DeploymentConfig config = configFactory.build(orchestratorType, workspace, envVars);

                    final String resourcePrefix = config.getResourcePrefix();
                    final List<ServicePort> servicePorts = config.getServicePorts();

                    createSecurityRules(azureClient, resourceGroupName, resourcePrefix, servicePorts, logger);

                    createLoadBalancerRules(azureClient, resourceGroupName, resourcePrefix, servicePorts, logger);
                    return CommandState.Success;
                }
            });
            context.setCommandState(state);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            context.logError(e);
        }
    }

    @VisibleForTesting
    static int filterPortsToOpen(
            Collection<NetworkSecurityRule> rules,
            Set<Integer> portsToOpen,
            PrintStream logger) throws InvalidConfigException {
        int maxPriority = Integer.MIN_VALUE;
        for (NetworkSecurityRule rule : rules) {
            final int priority = rule.priority();
            if (priority > maxPriority) {
                maxPriority = priority;
            }

            if (!SecurityRuleDirection.INBOUND.equals(rule.direction())) {
                // Ignore outbound rules
                continue;
            }

            if (!SecurityRuleAccess.ALLOW.equals(rule.access())) {
                // If user denied a port explicitly, we honor this rule and won't disable or modify it automatically
                // even it might be in our ports-to-open list, as it's hard for us to guess the user's intention without
                // knowledge of network topology and application details. So we just ignore it here.
                // If we create a conflicted allow rule later, it should have a higher priority number, which means
                // lower priority. It will have no affect in the worst case.
                continue;
            }

            final String ruleDestPortRange = rule.destinationPortRange();
            if (ruleDestPortRange.equals("*")) {
                // Already allow all
                logger.println(Messages.EnablePortCommand_securityRuleAlreadyAllowAll(
                        rule.name(), ruleDestPortRange));

                // No ports need to open
                portsToOpen.clear();

                break;
            } else if (ruleDestPortRange.contains("-")) {
                // Range
                final String[] parts = ruleDestPortRange.split("-", 2);
                int portStart = 0;
                int portEnd = 0;
                try {
                    portStart = Integer.parseInt(parts[0]);
                    portEnd = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ex) {
                    throw new InvalidConfigException(
                            Messages.EnablePortCommand_securityRuleInvalidDestinationPortRange(ruleDestPortRange));
                }

                Iterator<Integer> it = portsToOpen.iterator();
                while (it.hasNext()) {
                    final int port = it.next();
                    if (port >= portStart && port <= portEnd) {
                        // Port already allowed
                        logger.println(Messages.EnablePortCommand_securityRuleAlreadyAllowSingle(
                                rule.name(), ruleDestPortRange, String.valueOf(port)));
                        it.remove();
                    }
                }
            } else {
                // Single
                final int port = Integer.parseInt(ruleDestPortRange);
                if (portsToOpen.remove(port)) {
                    logger.println(Messages.EnablePortCommand_securityRuleAlreadyAllowSingle(
                            rule.name(), ruleDestPortRange, String.valueOf(port)));
                }
            }
        }

        return maxPriority;
    }

    static void createSecurityRules(
            Azure azureClient,
            String resourceGroupName,
            String resourcePrefix,
            List<ServicePort> servicePorts,
            PrintStream logger) throws IOException, InvalidConfigException {

        if (servicePorts.isEmpty()) {
            return;
        }

        Set<Integer> portsToOpen = new HashSet<Integer>();
        for (ServicePort servicePort : servicePorts) {
            portsToOpen.add(servicePort.getHostPort());
        }

        final PagedList<NetworkSecurityGroup> nsgs =
                azureClient.networkSecurityGroups().listByResourceGroup(resourceGroupName);

        // Find security group for public agents
        NetworkSecurityGroup nsgPublicAgent = null;
        for (NetworkSecurityGroup nsg : nsgs) {
            if (nsg.name().startsWith(resourcePrefix + "-agent-public-nsg-")) {
                nsgPublicAgent = nsg;
                break;
            }
        }

        if (nsgPublicAgent == null) {
            // Do nothing if security group not found
            logger.println(Messages.EnablePortCommand_securityGroupNotFound());
            return;
        }

        int maxPriority = filterPortsToOpen(nsgPublicAgent.securityRules().values(), portsToOpen, logger);

        // Create security rules for ports not opened
        final NetworkSecurityGroup.Update update = nsgPublicAgent.update();
        for (int port : portsToOpen) {
            logger.println(Messages.EnablePortCommand_securityRuleNotFound(String.valueOf(port)));

            maxPriority = maxPriority + Constants.PRIORITY_STEP;
            if (maxPriority > Constants.LOWEST_PRIORITY) {
                throw new InvalidConfigException(Messages.EnablePortCommand_exceedMaxPriority());
            }

            final String ruleName = "Allow_" + port;
            logger.println(Messages.EnablePortCommand_creatingRule(String.valueOf(port), ruleName));

            update.defineRule(ruleName)
                    .allowInbound()
                    .fromAddress("Internet")
                    .fromAnyPort()
                    .toAnyAddress()
                    .toPort(port)
                    .withAnyProtocol()
                    .withDescription(Messages.EnablePortCommand_allowTraffic(String.valueOf(port)))
                    .withPriority(maxPriority)
                    .attach();
        }

        update.apply();
    }

    static void createLoadBalancerRules(
            Azure azureClient,
            String resourceGroupName,
            String resourcePrefix,
            List<ServicePort> servicePorts,
            PrintStream logger) throws IOException, InvalidConfigException {

        if (servicePorts.isEmpty()) {
            return;
        }

        final PagedList<LoadBalancer> balancers = azureClient.loadBalancers().listByResourceGroup(resourceGroupName);

        LoadBalancer loadBalancer = null;
        for (LoadBalancer balancer : balancers) {
            final String balancerName = balancer.name();
            if (balancerName.startsWith(resourcePrefix + "-agent-lb-")) {
                if (balancer.backends().size() != 1 || balancer.frontends().size() != 1) {
                    throw new InvalidConfigException(Messages.EnablePortCommand_missMatch());
                }

                loadBalancer = balancer;
                break;
            }
        }

        if (loadBalancer == null) {
            // Do nothing if load balancer not found
            logger.println(Messages.EnablePortCommand_lbNotFound());
            return;
        }

        final LoadBalancerFrontend frontend = loadBalancer.frontends().values().iterator().next();
        final LoadBalancerBackend backend = loadBalancer.backends().values().iterator().next();
        final LoadBalancer.Update update = loadBalancer.update();

        for (ServicePort servicePort : servicePorts) {
            boolean ruleFound = false;
            for (LoadBalancingRule rule : loadBalancer.loadBalancingRules().values()) {
                if (servicePort.matchesLoadBalancingRule(rule)) {
                    logger.println(Messages.EnablePortCommand_lbFound(
                            String.valueOf(servicePort.getHostPort()), servicePort.getProtocol()));
                    ruleFound = true;
                    break;
                }
            }

            if (!ruleFound) {
                final String ruleName = "JLBRule" + servicePort.getProtocol().toString() + servicePort.getHostPort();
                logger.println(Messages.EnablePortCommand_creatingLB(
                        String.valueOf(servicePort.getHostPort()), ruleName));

                // Unfortunately there is no probe type of UDP, but it's mandatory. So always use TCP probe.
                final String probeName = "tcpPort" + servicePort.getHostPort() + "Probe";

                update
                        .defineTcpProbe(probeName)
                        .withPort(servicePort.getHostPort())
                        .attach()
                        .defineLoadBalancingRule(ruleName)
                        .withProtocol(servicePort.getTransportProtocol())
                        .fromFrontend(frontend.name())
                        .fromFrontendPort(servicePort.getHostPort())
                        .toBackend(backend.name())
                        .toBackendPort(servicePort.getHostPort())
                        .withProbe(probeName)
                        .withIdleTimeoutInMinutes(LOAD_BALANCER_IDLE_TIMEOUT_IN_MINUTES)
                        .withLoadDistribution(LoadDistribution.DEFAULT)
                        .attach();

            }
        }

        update.apply();
    }

    public interface IEnablePortCommandData extends IBaseCommandData {
        String getAzureCredentialsId();

        String getConfigFilePaths();

        String getResourceGroupName();

        ContainerServiceOrchestratorTypes getOrchestratorType();
    }
}
