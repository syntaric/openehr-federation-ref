// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * §11.4 / N37: a deployment MAY decline to offer best-effort at all. If it does,
 * a request for {@code partial} MUST be rejected rather than silently answered
 * all-or-nothing, and {@code OPTIONS} MUST say so — with no {@code opt_in}
 * block, since there is nothing to opt into.
 *
 * <p>Forks a Spring context for one property. That is the cost of testing the
 * mandatory reject path honestly: the default build offers the mode, and a
 * rejection that only exists in a unit test of the filter would leave the
 * OPTIONS half unverified.
 */
class BestEffortNotOfferedIT extends IntegrationTestBase {

    @DynamicPropertySource
    static void bestEffortOff(final DynamicPropertyRegistry registry) {
        registry.add("federation.completeness.offer-best-effort", () -> "false");
    }

    private static final String SUBJECT_QUERY =
            "SELECT c/uid/value AS composition_id FROM EHR e CONTAINS COMPOSITION c "
                    + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'";

    @Test
    @Tag("CP-30")
    @DisplayName("partial is rejected with 400, not ignored — and no node is asked")
    void partialIsRejectedNotIgnored() {
        stubQuery(NODE_1, "[]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);

        final ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.POST, "/v1/query/aql",
                Map.of("q", SUBJECT_QUERY), Map.of("openEHR-federation-completeness", "partial"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_HEADER_INVALID");
        assertThat(response.getBody().get("message").asText()).contains("not offered");
        NODE_1.verify(0, postRequestedFor(urlPathEqualTo("/v1/query/aql")));
        NODE_2.verify(0, postRequestedFor(urlPathEqualTo("/v1/query/aql")));
        NODE_3.verify(0, postRequestedFor(urlPathEqualTo("/v1/query/aql")));

        // `all` — the default, stated explicitly — is still accepted.
        final ResponseEntity<JsonNode> all = exchangeJson(HttpMethod.POST, "/v1/query/aql",
                Map.of("q", SUBJECT_QUERY), Map.of("openEHR-federation-completeness", "all"));
        assertThat(all.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @Tag("CP-23")
    @DisplayName("OPTIONS declares best_effort: false and omits opt_in, and still validates")
    void optionsDeclaresBestEffortFalseWithoutOptIn() {
        final ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.OPTIONS, "/v1/", null, Map.of());

        final JsonNode completeness = response.getBody().get("federation").get("completeness");
        assertThat(completeness.get("default").asText()).isEqualTo("all-or-nothing");
        assertThat(completeness.get("best_effort").asBoolean()).isFalse();
        assertThat(completeness.has("opt_in"))
                .as("nothing to opt into, so nothing to declare how")
                .isFalse();
        SpecSchemaValidator.assertValid(response.getBody(), SpecSchemaValidator.OPTIONS_ROOT);
    }
}
