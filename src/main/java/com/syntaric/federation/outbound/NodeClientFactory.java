// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.outbound.auth.OutboundAuthProviders;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;

/**
 * One shared JDK {@link HttpClient} (connect timeout from config, no redirect
 * following — Location must pass through untouched) wrapped per endpoint.
 */
@Component
public class NodeClientFactory {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OutboundAuthProviders authProviders;

    public NodeClientFactory(final FederationProperties properties, final OutboundAuthProviders authProviders) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeouts().connect())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.objectMapper = new ObjectMapper();
        this.authProviders = authProviders;
    }

    public NodeClient forEndpoint(final EndpointDescriptor endpoint) {
        return new NodeClient(endpoint, httpClient, objectMapper, authProviders);
    }

    public HttpClient httpClient() {
        return httpClient;
    }
}
