// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.syntaric.federation.query.EndpointDescriptor;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;

/**
 * Forwards the client's own bearer token to the node — suitable where nodes
 * share the gateway's token issuer. Identity propagation is trivially exact.
 */
@Component
public class PassthroughAuthProvider implements OutboundAuthProvider {

    @Override
    public String profile() {
        return "passthrough";
    }

    @Override
    public void apply(final HttpRequest.Builder request, final EndpointDescriptor endpoint, final String inboundAuthorization) {
        if (inboundAuthorization != null) {
            request.header("Authorization", inboundAuthorization);
        }
    }
}
