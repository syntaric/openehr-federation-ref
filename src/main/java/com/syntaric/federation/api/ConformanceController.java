// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api;

import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.FederationHeaders;
import com.syntaric.federation.identity.spi.NodeAddressingService;
import com.syntaric.federation.registry.Endpoint;
import com.syntaric.federation.registry.RegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * N30/CP-23: {@code OPTIONS {base}/} self-description — ITS-REST area map,
 * dedup modes, completion strategy, timeout policy, the stored-query registry
 * and the member endpoint list (federation membership information, never
 * patient data). The JSON shape follows the spec §7a.2 example verbatim.
 */
@RestController
public class ConformanceController {

    private final FederationProperties properties;
    private final RegistryService registry;
    private final NodeAddressingService addressing;

    public ConformanceController(final FederationProperties properties, final RegistryService registry,
                                 final NodeAddressingService addressing) {
        this.properties = properties;
        this.registry = registry;
        this.addressing = addressing;
    }

    @RequestMapping(method = RequestMethod.OPTIONS, path = {"/", "/v1", "/v1/"})
    public ResponseEntity<Map<String, Object>> selfDescription() {
        final Map<String, Object> federation = new LinkedHashMap<>();
        federation.put("id", properties.federation().id());
        // §7a.2: major.minor only — patch releases are editorial and do not change
        // the wire contract, so this stays "0.9" across 0.9.x. It tracks minor
        // releases whether or not they change the contract: 0.3 → 0.4 did (rows
        // became ITS-REST arrays), 0.4 → 0.9 did not, and reports only which
        // release this gateway was built against.
        federation.put("spec_version", "0.9");
        // §7a.2: the targeting mechanisms are deliberately NOT declared. Both the
        // FROM ENDPOINT directive and the openEHR-federation-endpoint header are
        // mandatory at every conformant gateway (N35), so the only conformant
        // declaration would be "both" and a client learns nothing from it.
        federation.put("aql", Map.of("fan_out", true));
        federation.put("dedup", Map.of(
                "default", "none",
                "modes", List.of("none", "version-identity"),
                "request_header", "openEHR-federation-dedup"));
        federation.put("timeout", Map.of(
                "per_node_ms", properties.timeouts().perNode().toMillis(),
                "overall_ms", properties.timeouts().overallBudget().toMillis(),
                "policy", "all-or-nothing"));
        // §7a.2 / §11.4: all-or-nothing is the default and needs no declaration
        // beyond `default`; what a deployment declares is whether the opt-in
        // best-effort mode exists and, if so, how a client selects it.
        final boolean bestEffort = properties.completeness().offerBestEffort();
        final Map<String, Object> completeness = new LinkedHashMap<>();
        completeness.put("default", "all-or-nothing");
        completeness.put("best_effort", bestEffort);
        if (bestEffort) {
            completeness.put("opt_in", Map.of(
                    "header", FederationHeaders.COMPLETENESS,
                    "value", FederationHeaders.COMPLETENESS_PARTIAL));
        }
        federation.put("completeness", completeness);
        // N39 / §11.6.2. "reject" is the fan-out behaviour; a directed
        // single-node query passes OFFSET through to that node.
        federation.put("paging", Map.of("offset_strategy", "reject"));
        // §11.6.3: none are decomposed across nodes. An aggregate is answered
        // only when a directive pins the query to a single node.
        federation.put("aggregates", Map.of("decomposable", List.of()));
        // N43: no fan-out template upload (DefinitionController). N44 / §12.7:
        // the gateway holds stored queries in its own registry and expands them
        // into a fan-out when invoked by name; it does not distribute the
        // definitions to nodes. The three are independent booleans by design.
        final Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("fan_out_template_upload", false);
        definition.put("stored_query_registry", true);
        definition.put("stored_query_fan_out", false);
        federation.put("definition", definition);
        // §14.1: what an unanswered localizer means here.
        federation.put("localization", Map.of(
                "on_failure", properties.localization().onFailure()
                        .name().toLowerCase(Locale.ROOT).replace('_', '-')));
        // §13.1: the JWKS must be discoverable, not merely published. Omitted
        // rather than faked when the deployment has not configured one.
        putIfPresent(federation, "auth", properties.federation().jwksUri() == null ? null
                : Map.of("jwks_uri", properties.federation().jwksUri()));
        // §7a.2 definition-area-split: `its_rest.definition` describes the area,
        // `definition.stored_query_registry` one artefact within it — templates
        // route to one node, stored queries live here. Free-form by design.
        final Map<String, Object> itsRest = new LinkedHashMap<>();
        itsRest.put("query", "federated");
        itsRest.put("ehr", "routed");
        itsRest.put("definition", "routed-single-node; stored queries at the gateway registry");
        itsRest.put("demographic", "unsupported");
        federation.put("its_rest", itsRest);

        final List<Map<String, Object>> endpoints = new ArrayList<>();
        for (final EndpointDescriptor descriptor : addressing.activeMembers()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", descriptor.endpointId());
            // §7a.2 SHOULD: the owning node, which is not the endpoint id.
            putIfPresent(entry, "node_id", descriptor.nodeId());
            putIfPresent(entry, "system_id", descriptor.systemId());
            putIfPresent(entry, "organisation", descriptor.organisation());
            // Membership and health, NOT the per-query §11.1 vocabulary
            // (§7a.2 endpoint-membership-status): `active` here says this
            // member is in service, and does not predict `active` there.
            entry.put("status", "active");
            // §7a.2: separate fields, matching §9.4's endpoint meta — not a
            // concatenated display string, which a client cannot decompose.
            putIfPresent(entry, "product", descriptor.product());
            putIfPresent(entry, "version", descriptor.version());
            registry.endpoint(descriptor.endpointId())
                    .map(Endpoint::latencyP50Ms)
                    .ifPresent(p50 -> {
                        if (p50 != null) {
                            entry.put("latency_ms_p50", p50);
                        }
                    });
            endpoints.add(entry);
        }

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("federation", federation);
        body.put("endpoints", endpoints);
        return ResponseEntity.ok()
                .header("Allow", "OPTIONS")
                .body(body);
    }

    private static void putIfPresent(final Map<String, Object> map, final String key, final Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
