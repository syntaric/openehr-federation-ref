// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;

/**
 * Fixed per-endpoint bearer tokens from {@code federation.security.static-tokens}
 * (keyed by endpoint id, with {@code default} as fallback). Dev/test only.
 */
@Component
public class StaticTokenAuthProvider implements OutboundAuthProvider {

    private final FederationProperties properties;

    public StaticTokenAuthProvider(final FederationProperties properties) {
        this.properties = properties;
    }

    @Override
    public String profile() {
        return "static";
    }

    @Override
    public void apply(final HttpRequest.Builder request, final EndpointDescriptor endpoint, final String inboundAuthorization) {
        final var tokens = properties.security().staticTokens();
        final String token = tokens.getOrDefault(endpoint.endpointId(), tokens.get("default"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
    }
}
