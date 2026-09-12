// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.impl;

import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.identity.spi.NodeAddressingService;
import com.syntaric.federation.registry.Endpoint;
import com.syntaric.federation.registry.Node;
import com.syntaric.federation.registry.Organisation;
import com.syntaric.federation.registry.RegistryService;
import com.syntaric.federation.security.OutboundCredentialsResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Registry-backed addressing (mCSD stays a named SPI slot, unimplemented in v1). */
@Component
public class RegistryAddressingService implements NodeAddressingService {

    private final RegistryService registry;
    private final OutboundCredentialsResolver credentials;

    public RegistryAddressingService(final RegistryService registry,
                                     final OutboundCredentialsResolver credentials) {
        this.registry = registry;
        this.credentials = credentials;
    }

    @Override
    public List<EndpointDescriptor> activeMembers() {
        return registry.activeEndpoints().stream().map(this::describe).toList();
    }

    @Override
    public Optional<EndpointDescriptor> byEndpointId(final String endpointId) {
        return registry.endpoint(endpointId).map(this::describe);
    }

    /**
     * Credentials are resolved <b>here</b>, where the endpoint row is already in
     * hand, rather than inside the auth provider. The decrypt is one AES-GCM
     * operation over a short string and it happens once per descriptor, so the
     * clinical path gains no query and no per-request work (Invariant 0); the
     * alternative — a provider that looks the endpoint up again — would add a
     * registry read to every federated call.
     */
    private EndpointDescriptor describe(final Endpoint endpoint) {
        final Node node = registry.node(endpoint.nodeId()).orElse(null);
        final Organisation organisation = node == null || node.organisationId() == null
                ? null
                : registry.organisation(node.organisationId()).orElse(null);
        return EndpointDescriptor.of(endpoint, node, organisation, credentials.resolve(endpoint));
    }
}
