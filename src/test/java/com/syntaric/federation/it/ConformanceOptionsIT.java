// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.syntaric.federation.registry.Endpoint;
import com.syntaric.federation.registry.EndpointRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** M1 verification: {@code OPTIONS {base}/} returns the spec-shaped self-description. */
class ConformanceOptionsIT extends IntegrationTestBase {

    @Test
    @Tag("CP-23")
    @Tag("CP-25")
    void optionsReturnsSpecShapedSelfDescription() {
        ResponseEntity<JsonNode> response =
                exchangeJson(HttpMethod.OPTIONS, "/", null, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode federation = response.getBody().get("federation");
        assertThat(federation.get("id").asText()).isEqualTo("reference-federation");
        assertThat(federation.get("aql").get("fan_out").asBoolean()).isTrue();
        assertThat(federation.get("aql").get("endpoint_directive").asBoolean()).isTrue();
        assertThat(federation.get("aql").get("endpoint_header").asBoolean()).isTrue();
        assertThat(federation.get("dedup").get("default").asText()).isEqualTo("none");
        List<String> modes = new ArrayList<>();
        federation.get("dedup").get("modes").forEach(m -> modes.add(m.asText()));
        assertThat(modes).containsExactly("none", "version-identity");
        // N38: both timeout values are mandatory and discoverable
        assertThat(federation.get("timeout").get("per_node_ms").asLong()).isEqualTo(1500);
        assertThat(federation.get("timeout").get("overall_ms").asLong()).isEqualTo(2500);
        assertThat(federation.get("timeout").get("policy").asText()).isEqualTo("best-effort");
        // §7a.2: spec_version is major.minor — a 0.4.x gateway reports "0.4"
        // and a client MUST NOT match on a patch component. It moved 0.3 → 0.4
        // with the ITS-REST row-shape correction, which did change the contract.
        assertThat(federation.get("spec_version").asText()).isEqualTo("0.4");
        assertThat(federation.get("spec_version").asText())
                .as("major.minor only — a patch component here would break clients")
                .matches("\\d+\\.\\d+");
        // §11.6.2 / N39: the OFFSET strategy must be declared
        assertThat(federation.get("paging").get("offset_strategy").asText()).isEqualTo("reject");
        // N32/CP-25: area map declares demographic as unsupported
        JsonNode itsRest = federation.get("its_rest");
        assertThat(itsRest.get("query").asText()).isEqualTo("federated");
        assertThat(itsRest.get("ehr").asText()).isEqualTo("routed");
        assertThat(itsRest.get("definition").asText()).isEqualTo("routed-single-node");
        assertThat(itsRest.get("demographic").asText()).isEqualTo("unsupported");
    }

    /**
     * §7a.2: "The response MUST additionally declare every behaviour this
     * specification requires be declared under N30." Each of these was a
     * behaviour this gateway already had and simply never stated — the
     * declaration obligation is what spec 0.3.1 added.
     */
    @Test
    @Tag("CP-23")
    void optionsDeclaresEveryBehaviourN30RequiresDeclared() {
        ResponseEntity<JsonNode> response =
                exchangeJson(HttpMethod.OPTIONS, "/", null, Map.of());
        JsonNode federation = response.getBody().get("federation");

        // N37: whether all-or-nothing completeness is offered
        assertThat(federation.get("completeness").get("all_or_nothing").asBoolean()).isTrue();
        // §11.6.3: which aggregates are decomposable across nodes — none are
        assertThat(federation.get("aggregates").get("decomposable")).isEmpty();
        // N43: whether fan-out template upload is offered
        assertThat(federation.get("definition").get("fan_out_template_upload").asBoolean()).isFalse();
        // §14.1: what an unanswered localizer means. "closed" is the spec
        // default and this build's; the point is that it is now *declared*.
        assertThat(federation.get("localization").get("on_failure").asText()).isEqualTo("closed");
    }

    /**
     * §13.1: the JWKS "must be discoverable, not merely published" — the gateway
     * MUST report its location so a node can verify our RFC 7523 assertions
     * without an out-of-band arrangement. This deployment configures none, so
     * the key is absent rather than fabricated: declaring a location we do not
     * serve would be worse than declaring nothing.
     */
    @Test
    @Tag("CP-23")
    void optionsOmitsJwksLocationWhenNoneIsConfigured() {
        ResponseEntity<JsonNode> response =
                exchangeJson(HttpMethod.OPTIONS, "/", null, Map.of());

        assertThat(response.getBody().get("federation").has("auth")).isFalse();
    }

    @Test
    @Tag("CP-23")
    void optionsListsMemberEndpointsWithoutRequiringAPatientIdentifier() {
        ResponseEntity<JsonNode> response =
                exchangeJson(HttpMethod.OPTIONS, "/", null, Map.of());

        JsonNode endpoints = response.getBody().get("endpoints");
        assertThat(endpoints).hasSize(3);
        List<String> ids = new ArrayList<>();
        endpoints.forEach(e -> {
            ids.add(e.get("id").asText());
            assertThat(e.get("organisation").asText()).isNotEmpty();
            // §7a.2 endpoint-membership-status: this status is membership and
            // health, NOT the per-query §11.1 vocabulary. `not-localized` is
            // meaningless here.
            assertThat(e.get("status").asText()).isEqualTo("active");
            // §7a.2 SHOULD: the owning node_id, distinct from the endpoint id.
            assertThat(e.get("node_id").asText()).isNotEmpty();
        });
        assertThat(ids).containsExactlyInAnyOrder("node_1", "node_2", "node_3");
    }

    /**
     * A known p50 is surfaced; an unknown one is omitted rather than reported as
     * zero.
     *
     * <p>The distinction is the point. {@code latency_ms_p50} is advisory
     * information a client may use to set its own expectations, and a node that
     * has simply never been measured must not be described as instantaneous —
     * omission says "no data", which is the honest answer.
     *
     * <p><b>Nothing in this build writes the column.</b> Aggregating observed
     * latency into a p50 is a telemetry concern this gateway does not implement;
     * the value is registry data, supplied by whatever does the measuring. So
     * this seeds it directly, which is also exactly how a deployment would.
     */
    @Test
    @Tag("CP-23")
    void aKnownLatencyP50IsSurfacedAndAnUnknownOneIsOmitted() {
        Endpoint measured = endpoints.findById("node_1").orElseThrow();
        endpoints.save(Endpoint.withoutCredentials(measured.endpointId(), measured.nodeId(),
                measured.baseUrl(), measured.pixManagerUrl(), measured.connectionType(),
                measured.authProfile(), measured.status(), 42L));

        ResponseEntity<JsonNode> response =
                exchangeJson(HttpMethod.OPTIONS, "/", null, Map.of());

        Map<String, JsonNode> byId = new java.util.HashMap<>();
        response.getBody().get("endpoints").forEach(e -> byId.put(e.get("id").asText(), e));

        assertThat(byId.get("node_1").get("latency_ms_p50").asLong())
                .as("a measured node reports what was measured")
                .isEqualTo(42L);
        assertThat(byId.get("node_2").has("latency_ms_p50"))
                .as("an unmeasured node omits the field — never a misleading zero")
                .isFalse();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private EndpointRepository endpoints;
}
