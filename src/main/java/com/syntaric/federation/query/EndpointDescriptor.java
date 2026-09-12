// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import com.syntaric.federation.registry.Endpoint;
import com.syntaric.federation.registry.Node;
import com.syntaric.federation.registry.Organisation;

/**
 * Registry-derived facts about one endpoint, as needed for meta/row annotation
 * and for outbound authentication.
 *
 * <p>{@link #credentials()} is why the token-acquiring auth providers no longer
 * read global configuration: the credentials arrive with the endpoint they
 * belong to, so two nodes with two separate authorization servers are simply two
 * descriptors rather than a deployment that can only ever register one.
 */
public record EndpointDescriptor(
        String endpointId,
        String nodeId,
        String systemId,
        String organisation,
        String url,
        String pixManagerUrl,
        String authProfile,
        String product,
        String version,
        OutboundCredentials credentials) {

    /** Never null, so providers can read through without a null check. */
    public EndpointDescriptor {
        credentials = credentials == null ? OutboundCredentials.NONE : credentials;
    }

    /**
     * Without credentials — for the callers that only annotate rows and never
     * authenticate (localization, meta). Keeps those construction sites from
     * carrying a parameter they have nothing to put in.
     */
    public EndpointDescriptor(final String endpointId, final String nodeId, final String systemId, final String organisation,
                              final String url, final String pixManagerUrl, final String authProfile,
                              final String product, final String version) {
        this(endpointId, nodeId, systemId, organisation, url, pixManagerUrl, authProfile,
                product, version, OutboundCredentials.NONE);
    }

    /**
     * @param credentials decrypted by {@code OutboundCredentialsResolver}; pass
     *                    {@link OutboundCredentials#NONE} when the caller has no
     *                    need to authenticate.
     */
    public static EndpointDescriptor of(final Endpoint endpoint, final Node node, final Organisation organisation,
                                        final OutboundCredentials credentials) {
        return new EndpointDescriptor(
                endpoint.endpointId(),
                node != null ? node.nodeId() : null,
                node != null ? node.systemId() : null,
                organisation != null ? organisation.name() : null,
                endpoint.baseUrl(),
                endpoint.pixManagerUrl(),
                endpoint.authProfile(),
                node != null ? node.product() : null,
                node != null ? node.version() : null,
                credentials);
    }

    public static EndpointDescriptor of(final Endpoint endpoint, final Node node, final Organisation organisation) {
        return of(endpoint, node, organisation, OutboundCredentials.NONE);
    }
}
