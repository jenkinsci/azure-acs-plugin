/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs;

import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsClientFactory;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsUtils;
import com.microsoft.jenkins.azurecommons.telemetry.AzureHttpRecorder;
import hudson.Plugin;
import hudson.model.Run;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AzureACSPlugin extends Plugin {
    public static void sendEventFor(String action, String orchestrator, Run<?, ?> run, String... properties) {
        final Map<String, String> props = new HashMap<>();
        props.put(Constants.AI_ORCHESTRATOR, orchestrator);
        props.put(Constants.AI_RUN, AppInsightsUtils.hash(run == null ? null : run.getUrl()));
        for (int i = 1; i < properties.length; i += 2) {
            props.put(properties[i - 1], properties[i]);
        }
        sendEvent(Constants.AI_ACS, action, props);
    }

    public static void sendEvent(final String item, final String action, final String... properties) {
        final Map<String, String> props = new HashMap<>();
        for (int i = 1; i < properties.length; i += 2) {
            props.put(properties[i - 1], properties[i]);
        }
        sendEvent(item, action, props);
    }

    public static void sendEvent(final String item, final String action, final Map<String, String> properties) {
        AppInsightsClientFactory.getInstance(AzureACSPlugin.class)
                .sendEvent(item, action, properties, false);
    }

    public static String normalizeContainerSerivceType(String type) {
        if (Constants.AI_KUBERNETES.equalsIgnoreCase(type)) {
            return Constants.AI_KUBERNETES;
        } else if (Constants.AI_DCOS.equalsIgnoreCase(type)) {
            return Constants.AI_DCOS;
        } else if (Constants.AI_SWARM.equalsIgnoreCase(type)) {
            return Constants.AI_SWARM;
        } else if (Constants.AI_AKS.equalsIgnoreCase(type)) {
            return Constants.AI_AKS;
        } else {
            return Constants.AI_CUSTOM;
        }
    }

    public static class AzureTelemetryInterceptor implements Interceptor {
        @Override
        public Response intercept(final Chain chain) throws IOException {
            final Request request = chain.request();
            final Response response = chain.proceed(request);
            new AzureHttpRecorder(AppInsightsClientFactory.getInstance(AzureACSPlugin.class))
                    .record(new AzureHttpRecorder.HttpRecordable()
                            .withHttpCode(response.code())
                            .withHttpMessage(response.message())
                            .withHttpMethod(request.method())
                            .withRequestUri(request.url().uri())
                            .withRequestId(response.header("x-ms-request-id"))
                    );
            return response;
        }
    }
}
