// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.syntaric.federation.query.EndpointDescriptor;

import java.net.http.HttpRequest;

/**
 * N24/N25: on every dispatched request the gateway authenticates onward and
 * propagates the client identity. The wire mechanism is deployment-defined —
 * hence an SPI keyed by the endpoint's {@code auth_profile}.
 */
public interface OutboundAuthProvider {

    /** Profile name this provider serves (matched against {@code endpoint.auth_profile}). */
    String profile();

    /**
     * Adds outbound credentials to the request being composed.
     *
     * @param inboundAuthorization the client's inbound {@code Authorization} header
     *                             value, or null (identity propagation input)
     */
    void apply(final HttpRequest.Builder request, final EndpointDescriptor endpoint, final String inboundAuthorization);
}
