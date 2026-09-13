// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api;

import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
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
 * targeting mechanisms, dedup modes, timeout policy and the member endpoint
 * list (federation membership information, never patient data). The JSON shape
 * follows the spec §7a.2 example verbatim.
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
        federation.put("aql", Map.of(
                "fan_out", true,
                "endpoint_directive", true,
                "endpoint_header", true));
        federation.put("dedup", Map.of(
                "default", "none",
                "modes", List.of("none", "version-identity"),
                "request_header", "openEHR-federation-dedup"));
        federation.put("timeout", Map.of(
                "per_node_ms", properties.timeouts().perNode().toMillis(),
                "overall_ms", properties.timeouts().overallBudget().toMillis(),
                "policy", "best-effort"));
        // §7a.2: every behaviour the spec requires be declared under N30.
        federation.put("completeness", Map.of(
                "default", "best-effort",
                "all_or_nothing", true,
                "opt_in", Map.of("header", "openEHR-federation-completeness", "value", "all")));
        // N39 / §11.6.2. "reject" is the fan-out behaviour; a directed
        // single-node query passes OFFSET through to that node.
        federation.put("paging", Map.of("offset_strategy", "reject"));
        // §11.6.3: none are decomposed across nodes. An aggregate is answered
        // only when a directive pins the query to a single node.
        federation.put("aggregates", Map.of("decomposable", List.of()));
        // N43: no fan-out template upload (DefinitionController).
        federation.put("definition", Map.of("fan_out_template_upload", false));
        // §14.1: what an unanswered localizer means here.
        federation.put("localization", Map.of(
                "on_failure", properties.localization().onFailure()
                        .name().toLowerCase(Locale.ROOT).replace('_', '-')));
        // §13.1: the JWKS must be discoverable, not merely published. Omitted
        // rather than faked when the deployment has not configured one.
        putIfPresent(federation, "auth", properties.federation().jwksUri() == null ? null
                : Map.of("jwks_uri", properties.federation().jwksUri()));
        federation.put("its_rest", Map.of(
                "query", "federated",
                "ehr", "routed",
                "definition", "routed-single-node",
                "demographic", "unsupported"));

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
