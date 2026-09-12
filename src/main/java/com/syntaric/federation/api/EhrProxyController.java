// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.FederationRequestContext;
import com.syntaric.federation.query.fanout.NodeOutcome;
import com.syntaric.federation.query.routing.EhrIdRouter;
import com.syntaric.federation.query.routing.FollowUpRouter;
import com.syntaric.federation.query.routing.RoutingDecision;
import com.syntaric.federation.query.routing.VersionUidParser;
import com.syntaric.federation.identity.spi.NodeAddressingService;
import com.syntaric.federation.outbound.NodeClientFactory;
import com.syntaric.federation.outbound.proxy.ProxyService;
import com.syntaric.federation.registry.RegistryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single-node routed {@code /v1/ehr/**} surface (§7a.1): resolve the owning
 * node (§12.5), stream the request through byte-identical (N22/N31), then
 * learn from what came back ({@code system_id} mappings, ehr index).
 */
@RestController
public class EhrProxyController {

    private static final Logger log = LoggerFactory.getLogger(EhrProxyController.class);

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final EhrIdRouter ehrIdRouter;
    private final FollowUpRouter followUpRouter;
    private final ProxyService proxy;
    private final NodeAddressingService addressing;
    private final NodeClientFactory clientFactory;
    private final RegistryService registry;
    private final FederationProperties properties;

    public EhrProxyController(final EhrIdRouter ehrIdRouter, final FollowUpRouter followUpRouter,
                              final ProxyService proxy, final NodeAddressingService addressing,
                              final NodeClientFactory clientFactory, final RegistryService registry,
                              final FederationProperties properties) {
        this.ehrIdRouter = ehrIdRouter;
        this.followUpRouter = followUpRouter;
        this.proxy = proxy;
        this.addressing = addressing;
        this.clientFactory = clientFactory;
        this.registry = registry;
        this.properties = properties;
    }

    @RequestMapping({"/v1/ehr", "/v1/ehr/**"})
    public void route(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        final long startedAt = System.nanoTime();
        final String path = request.getRequestURI();
        final boolean write = WRITE_METHODS.contains(request.getMethod());
        final FederationRequestContext context = RequestContexts.federation(request);

        EndpointDescriptor target;
        final String ehrId = ehrIdFromPath(path);

        if (ehrId == null) {
            // POST {base}/v1/ehr — new-EHR creation must name its node explicitly (N23).
            target = explicitTargetOnly(context,
                    "Creating a new EHR requires an explicit target endpoint "
                            + "(openEHR-federation-endpoint)");
        } else {
            final Optional<VersionUidParser.VersionUid> versionUid = VersionUidParser.findInPath(path);
            final RoutingDecision decision = versionUid.isPresent()
                    ? followUpRouter.route(versionUid.get(), context, write)
                    : ehrIdRouter.route(ehrId, context, write);
            target = switch (decision) {
                case RoutingDecision.SingleNode single -> single.endpoint();
                case RoutingDecision.AskAll askAll -> probe(ehrId, askAll.candidates(), request);
            };
        }

        final String routedEhrId = ehrId;
        final ProxyService.ProxyResult result = proxy.forward(request, response, target, path,
                r -> learn(target, routedEhrId, r));
    }

    private EndpointDescriptor explicitTargetOnly(final FederationRequestContext context, final String message) {
        if (context.headerEndpointIds().size() != 1) {
            throw new FederationException(FedErrorCode.FED_NO_TARGET, message);
        }
        final String endpointId = context.headerEndpointIds().iterator().next();
        return addressing.byEndpointId(endpointId)
                .orElseThrow(() -> new FederationException(FedErrorCode.FED_UNKNOWN_TARGET,
                        "Endpoint '" + endpointId + "' is not a member of this federation"));
    }

    /** N41 step 4 / N42: ask-all existence probe, reads only. */
    private EndpointDescriptor probe(final String ehrId, final List<EndpointDescriptor> candidates,
                                     final HttpServletRequest request) {
        final List<EndpointDescriptor> claimants = new ArrayList<>();
        for (final EndpointDescriptor candidate : candidates) {
            final boolean found = clientFactory.forEndpoint(candidate)
                    .probeEhr(ehrId, properties.timeouts().perNode(),
                            request.getHeader("Authorization"));
            if (found) {
                claimants.add(candidate);
            }
        }
        if (claimants.isEmpty()) {
            throw new FederationException(FedErrorCode.FED_NOT_FOUND,
                    "ehr_id could not be resolved to any member node");
        }
        if (claimants.size() > 1) {
            final String nodes = claimants.stream().map(EndpointDescriptor::endpointId)
                    .reduce((a, b) -> a + "," + b).orElse("");
            registry.raiseCollisionIncident(ehrId, nodes);
            throw new FederationException(FedErrorCode.FED_EHR_ID_COLLISION,
                    "ehr_id is claimed by more than one member node",
                    Map.of("ehr_id", ehrId, "nodes", nodes));
        }
        final EndpointDescriptor owner = claimants.get(0);
        if (owner.nodeId() != null) {
            registry.observeEhrOnNode(ehrId, owner.nodeId());
        }
        return owner;
    }

    /** Learn-on-observe (N21): system_id mappings from returned uids, ehr index from routing. */
    private void learn(final EndpointDescriptor target, final String ehrId, final ProxyService.ProxyResult result) {
        if (result.status() < 200 || result.status() >= 300 || target.nodeId() == null) {
            return;
        }
        if (ehrId != null) {
            registry.observeEhrOnNode(ehrId, target.nodeId());
        }
        if (result.etag() != null) {
            VersionUidParser.parse(result.etag().replace("\"", ""))
                    .ifPresent(uid -> registry.learnSystemId(uid.creatingSystemId(),
                            target.nodeId(), target.url()));
        }
        if (result.location() != null) {
            VersionUidParser.findInPath(result.location())
                    .ifPresent(uid -> registry.learnSystemId(uid.creatingSystemId(),
                            target.nodeId(), target.url()));
        }
    }

    private String ehrIdFromPath(final String path) {
        final String[] segments = path.split("/");
        // /v1/ehr/{ehr_id}/... -> ["", "v1", "ehr", "{ehr_id}", ...]
        return segments.length > 3 && !segments[3].isBlank() ? segments[3] : null;
    }
}
