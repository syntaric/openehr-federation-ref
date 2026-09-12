// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.routing;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.FederationRequestContext;
import com.syntaric.federation.identity.spi.NodeAddressingService;
import com.syntaric.federation.registry.EhrNodeIndexEntry;
import com.syntaric.federation.registry.RegistryService;
import com.syntaric.federation.registry.ResolutionBinding;
import com.syntaric.federation.registry.ResolutionBindingRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * N41 path-{@code ehr_id} routing, strict priority order: explicit target →
 * resolution binding → ehr_id index → (reads only) ask-all probe. For writes,
 * exhausting steps 1–3 yields 400, never a probe. A collision at any step is a
 * 409 + integrity incident (N42).
 */
@Component
public class EhrIdRouter {

    private final NodeAddressingService addressing;
    private final RegistryService registry;
    private final ResolutionBindingRepository bindings;

    public EhrIdRouter(final NodeAddressingService addressing, final RegistryService registry,
                       final ResolutionBindingRepository bindings) {
        this.addressing = addressing;
        this.registry = registry;
        this.bindings = bindings;
    }

    public RoutingDecision route(final String ehrId, final FederationRequestContext context, final boolean write) {
        // 1. explicit target on the request
        if (!context.headerEndpointIds().isEmpty()) {
            if (context.headerEndpointIds().size() > 1) {
                throw new FederationException(FedErrorCode.FED_NO_TARGET,
                        "A single-resource request must name exactly one endpoint");
            }
            final String endpointId = context.headerEndpointIds().iterator().next();
            final EndpointDescriptor endpoint = addressing.byEndpointId(endpointId)
                    .orElseThrow(() -> new FederationException(FedErrorCode.FED_UNKNOWN_TARGET,
                            "Endpoint '" + endpointId + "' is not a member of this federation"));
            return new RoutingDecision.SingleNode(endpoint, "explicit-target");
        }

        // 2. resolution binding held by the gateway
        final List<String> boundNodes = bindings.findByLocalEhrId(ehrId).stream()
                .map(ResolutionBinding::nodeId).distinct().toList();
        if (boundNodes.size() > 1) {
            throw collision(ehrId, boundNodes);
        }
        if (boundNodes.size() == 1) {
            final Optional<RoutingDecision> decision = decisionForNode(boundNodes.get(0), "resolution-binding");
            if (decision.isPresent()) {
                return decision.get();
            }
        }

        // 3. ehr_id -> node index
        final List<String> indexed = registry.ehrIndex(ehrId).stream()
                .map(EhrNodeIndexEntry::nodeId).distinct().toList();
        if (indexed.size() > 1) {
            throw collision(ehrId, indexed);
        }
        if (indexed.size() == 1) {
            final Optional<RoutingDecision> decision = decisionForNode(indexed.get(0), "ehr-index");
            if (decision.isPresent()) {
                return decision.get();
            }
        }

        // 4. ask-all probe — reads only
        if (write) {
            throw new FederationException(FedErrorCode.FED_NO_TARGET,
                    "Cannot unambiguously route this write; name the target endpoint via "
                            + "the openEHR-federation-endpoint header (N41)");
        }
        return new RoutingDecision.AskAll(addressing.activeMembers());
    }

    private Optional<RoutingDecision> decisionForNode(final String nodeId, final String basis) {
        return registry.endpointForNode(nodeId)
                .flatMap(e -> addressing.byEndpointId(e.endpointId()))
                .map(d -> new RoutingDecision.SingleNode(d, basis));
    }

    private FederationException collision(final String ehrId, final List<String> nodes) {
        // registry.observeEhrOnNode raises the incident; here the claim set is already >1
        final String claimants = String.join(",", nodes);
        registry.raiseCollisionIncident(ehrId, claimants);
        return new FederationException(FedErrorCode.FED_EHR_ID_COLLISION,
                "ehr_id is claimed by more than one member node",
                Map.of("ehr_id", ehrId, "nodes", claimants));
    }
}
