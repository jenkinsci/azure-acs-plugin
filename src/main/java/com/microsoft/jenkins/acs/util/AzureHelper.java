/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.util;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureBaseCredentials;
import com.microsoft.azure.util.AzureCredentialUtil;
import com.microsoft.jenkins.acs.AzureACSPlugin;
import com.microsoft.jenkins.azurecommons.core.AzureClientFactory;
import com.microsoft.jenkins.azurecommons.core.credentials.TokenCredentialData;
import hudson.model.Item;

/**
 * Helper methods on the Azure related constructs.
 */
public final class AzureHelper {

    public static TokenCredentialData getToken(Item owner, String credentialId) {
        AzureBaseCredentials credential = AzureCredentialUtil.getCredential(owner, credentialId);
        if (credential == null) {
            throw new IllegalStateException(
                    String.format("Can't find credential in scope %s with id: %s", owner, credentialId));
        }
        return TokenCredentialData.deserialize(credential.serializeToTokenData());
    }

    public static Azure buildClient(Item owner, String credentialId) {
        TokenCredentialData token = getToken(owner, credentialId);
        return buildClient(token);
    }

    public static Azure buildClient(TokenCredentialData token) {
        return AzureClientFactory.getClient(token, new AzureClientFactory.Configurer() {
            @Override
            public Azure.Configurable configure(Azure.Configurable configurable) {
                return configurable
                        .withInterceptor(new AzureACSPlugin.AzureTelemetryInterceptor())
                        .withUserAgent(AzureClientFactory.getUserAgent(Constants.PLUGIN_NAME,
                                AzureHelper.class.getPackage().getImplementationVersion()));
            }
        });
    }

    private AzureHelper() {
        // hide constructor
    }
}
