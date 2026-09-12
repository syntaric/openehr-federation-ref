// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.routing;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.FederationRequestContext;
import com.syntaric.federation.identity.spi.NodeAddressingService;
import com.syntaric.federation.registry.Node;
import com.syntaric.federation.registry.RegistryService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * N22/N23/N36 follow-up routing for version-addressed resources. Reads route
 * by {@code creating_system_id} → carried {@code endpoint_id} → ask-all.
 * Versioned writes go only to the controlling CDR
 * ({@code system_id == creating_system_id}); anything else is 409 naming the
 * controlling system — never a write to a copy holder.
 */
@Component
public class FollowUpRouter {

    private final RegistryService registry;
    private final NodeAddressingService addressing;

    public FollowUpRouter(final RegistryService registry, final NodeAddressingService addressing) {
        this.registry = registry;
        this.addressing = addressing;
    }

    public RoutingDecision route(final VersionUidParser.VersionUid uid,
                                 final FederationRequestContext context,
                                 final boolean write) {
        final Optional<Node> controlling = registry.nodeForCreatingSystemId(uid.creatingSystemId());

        if (write) {
            final Node node = controlling.orElseThrow(() -> wrongControllingSystem(uid));
            final EndpointDescriptor endpoint = registry.endpointForNode(node.nodeId())
                    .flatMap(e -> addressing.byEndpointId(e.endpointId()))
                    .orElseThrow(() -> wrongControllingSystem(uid));
            // An explicit header target that contradicts the controlling system is a 409,
            // not a silent redirect (N36).
            if (!context.headerEndpointIds().isEmpty()
                    && !context.headerEndpointIds().contains(endpoint.endpointId())) {
                throw wrongControllingSystem(uid);
            }
            return new RoutingDecision.SingleNode(endpoint, "creating_system_id");
        }

        // reads: creating_system_id first …
        final Optional<RoutingDecision.SingleNode> byOwner = controlling
                .flatMap(node -> registry.endpointForNode(node.nodeId()))
                .flatMap(e -> addressing.byEndpointId(e.endpointId()))
                .map(d -> new RoutingDecision.SingleNode(d, "creating_system_id"));
        if (byOwner.isPresent()) {
            return byOwner.get();
        }
        // … then a carried endpoint_id …
        if (context.headerEndpointIds().size() == 1) {
            final String endpointId = context.headerEndpointIds().iterator().next();
            final Optional<EndpointDescriptor> endpoint = addressing.byEndpointId(endpointId);
            if (endpoint.isPresent()) {
                return new RoutingDecision.SingleNode(endpoint.get(), "endpoint-id");
            }
        }
        // … then ask-all (reads only).
        return new RoutingDecision.AskAll(addressing.activeMembers());
    }

    private FederationException wrongControllingSystem(final VersionUidParser.VersionUid uid) {
        return new FederationException(FedErrorCode.FED_WRONG_CONTROLLING_SYSTEM,
                "This versioned object is controlled by system '" + uid.creatingSystemId()
                        + "'; a write may only be applied there (N36)",
                Map.of("controlling_system_id", uid.creatingSystemId()));
    }
}
