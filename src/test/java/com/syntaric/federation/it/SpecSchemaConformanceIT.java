// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conformance against the specification's published JSON Schemas (§9.1, §7a.2).
 *
 * <p>Every other IT in this package asserts <em>particular</em> facts about a
 * response. This one asserts the whole shape at once, against the artifact the
 * specification publishes as the contract — so a member that is required, or an
 * enum value that is misspelled, fails here even when no hand-written assertion
 * happens to look at it.
 *
 * <p>The responses are driven through the same paths the other ITs use, but the
 * point is deliberately not the scenario: it is that each scenario's output is a
 * conformant {@code RESULT_SET} / {@code OPTIONS} body. Cases were chosen to
 * exercise the schema's <em>conditional</em> rules, which a single happy-path
 * response would leave untested — an errored endpoint (requires {@code error}),
 * a never-asked endpoint (must <em>omit</em> {@code latency_ms}), and an empty
 * result set.
 */
class SpecSchemaConformanceIT extends IntegrationTestBase {

    // PATIENT_ID is inherited from IntegrationTestBase, which registers its
    // per-node ehr_id mappings — redeclaring it here would silently give every
    // node `not-resolved` and test nothing.
    private static final String SUBJECT_QUERY =
            "SELECT c/uid/value AS composition_id, c/context/start_time/value AS start_time "
                    + "FROM EHR e CONTAINS COMPOSITION c "
                    + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'";

    private ResponseEntity<JsonNode> query(String aql, Map<String, String> headers) {
        return exchangeJson(HttpMethod.POST, "/v1/query/aql", Map.of("q", aql), headers);
    }

    @Test
    @Tag("CP-35")
    void federatedResultSetValidatesAgainstThePublishedEnvelopeSchema() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1",
                "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[" + row("bbbbbbbb-2222-0000-0000-000000000001::cdr2.test::1",
                "2026-01-01T11:00") + "]", 0);
        stubQuery(NODE_3, "[]", 0);

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        SpecSchemaValidator.assertValid(response.getBody(), SpecSchemaValidator.RESULT_SET);
    }

    /**
     * The ITS-REST rule the 0.3.1 example got wrong, asserted directly rather
     * than only through the schema: {@code rows} entries are ordered arrays
     * whose length matches {@code columns[]} (§9.1, finding F3).
     */
    @Test
    @Tag("CP-35")
    void rowsAreOrderedArraysMatchingColumnsInLength() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1",
                "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);

        JsonNode body = query(SUBJECT_QUERY, Map.of()).getBody();

        assertThat(body.get("rows")).isNotEmpty();
        int columnCount = body.get("columns").size();
        body.get("rows").forEach(r -> {
            assertThat(r.isArray())
                    .as("ITS-REST ResultSetRow is an array of values, not an object")
                    .isTrue();
            assertThat(r.size())
                    .as("rows[n][i] is the value of columns[i], so the lengths must agree")
                    .isEqualTo(columnCount);
        });
        SpecSchemaValidator.assertValid(body, SpecSchemaValidator.RESULT_SET);
    }

    /**
     * §9.1/N17 as amended: the federation contributes exactly one member to
     * {@code meta} — {@code federation} — and everything it adds lives inside
     * it. The flat form releases up to 0.9.0 emitted is now a CP-35 failure,
     * and the {@code _} prefix stays reserved to openEHR, so {@code _federation}
     * is as wrong as {@code _complete} was.
     */
    @Test
    @Tag("CP-35")
    void federationMetaAdditionsAreNestedAndNotUnderscorePrefixed() {
        stubQuery(NODE_1, "[]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);

        JsonNode meta = query(SUBJECT_QUERY, Map.of()).getBody().get("meta");

        assertThat(meta.has("federation")).isTrue();
        assertThat(meta.get("federation").has("complete")).isTrue();
        assertThat(meta.get("federation").has("endpoints")).isTrue();
        for (String flat : List.of("complete", "endpoints", "timeout", "dedup")) {
            assertThat(meta.has(flat))
                    .as("%s: the pre-amendment flat key must not be emitted beside the nested form", flat)
                    .isFalse();
        }
        for (String reserved : List.of("_federation", "_complete", "_endpoints", "_timeout", "_dedup")) {
            assertThat(meta.has(reserved))
                    .as("%s: the `_` prefix is reserved to openEHR-defined fields", reserved)
                    .isFalse();
        }
    }

    /**
     * §11.4 failure-carries-envelope: a 504 under the default strategy MUST
     * carry {@code meta.federation.endpoints[]} and {@code complete: false}. The
     * error body is not a RESULT_SET, so the {@code meta} is validated by
     * wrapping it in the smallest instance the envelope schema accepts — which
     * is exactly the shape a client reading the error would rely on.
     */
    @Test
    @Tag("CP-35")
    @Tag("CP-30")
    void failingResponseMetaValidatesAgainstTheEnvelopeSchema() {
        stubQuery(NODE_1, "[]", 0);
        stubQuery(NODE_2, "[]", 3000);
        stubQuery(NODE_3, "[]", 0);

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(504);
        JsonNode meta = response.getBody().get("meta");
        assertThat(meta.get("federation").get("complete").asBoolean()).isFalse();
        com.fasterxml.jackson.databind.node.ObjectNode wrapped =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        wrapped.putArray("rows");
        wrapped.set("meta", meta);
        SpecSchemaValidator.assertValid(wrapped, SpecSchemaValidator.RESULT_SET);
    }

    /**
     * A member that is genuinely unreachable — a dead port, not a slow stub —
     * must still produce an {@code offline} entry with a non-empty {@code error},
     * which the schema requires. The other failure ITs use a delayed or a 500
     * WireMock stub; this was the one shape that only the demo stack exercised,
     * and it was wrong there: the JDK's refused-connection exception carries an
     * empty message.
     */
    @Test
    @Tag("CP-35")
    @Tag("CP-30")
    void unreachableMemberIsOfflineWithAnErrorAndStillValidates() {
        stubQuery(NODE_1, "[]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);
        // Port 1 is reserved and nothing listens there; the connect fails at once.
        fixture.source("org-b", "node_dead", "cdr-dead.test", "http://127.0.0.1:1");
        try {
            // Subject-less, so every active member is dispatched to, node_dead included.
            String undirected = "SELECT c/uid/value AS composition_id FROM EHR e CONTAINS COMPOSITION c";

            ResponseEntity<JsonNode> failed = query(undirected, Map.of());
            assertThat(failed.getStatusCode().value()).isEqualTo(504);
            JsonNode dead = endpoint(failed.getBody().get("meta"), "node_dead");
            assertThat(dead.get("status").asText()).isEqualTo("offline");
            assertThat(dead.hasNonNull("error")).isTrue();
            assertThat(dead.get("error").asText()).isNotBlank();
            com.fasterxml.jackson.databind.node.ObjectNode wrapped =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            wrapped.putArray("rows");
            wrapped.set("meta", failed.getBody().get("meta"));
            SpecSchemaValidator.assertValid(wrapped, SpecSchemaValidator.RESULT_SET);

            ResponseEntity<JsonNode> partial = query(undirected,
                    Map.of("openEHR-federation-completeness", "partial"));
            assertThat(partial.getStatusCode().value()).isEqualTo(200);
            SpecSchemaValidator.assertValid(partial.getBody(), SpecSchemaValidator.RESULT_SET);
        } finally {
            fixture.removeSource("node_dead", "node_dead");
        }
    }

    private static JsonNode endpoint(JsonNode meta, String id) {
        for (JsonNode e : meta.get("federation").get("endpoints")) {
            if (id.equals(e.get("id").asText())) {
                return e;
            }
        }
        throw new AssertionError("endpoint " + id + " not in meta.federation.endpoints");
    }

    /** §12.7 / §9.1: a stored query's envelope carries ITS-REST's {@code name} and still validates. */
    @Test
    @Tag("CP-35")
    @Tag("CP-40")
    void storedQueryEnvelopeValidatesAgainstThePublishedSchema() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1",
                "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);
        String name = "schema" + java.util.UUID.randomUUID().toString().replace("-", "") + "::encounters";
        ResponseEntity<JsonNode> stored = exchangeJson(HttpMethod.PUT,
                "/v1/definition/query/" + name + "/1.0.0",
                Map.of("q", SUBJECT_QUERY.replace("'" + PATIENT_ID + "'", "$patient_id")), Map.of());
        assertThat(stored.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.POST, "/v1/query/" + name,
                Map.of("query_parameters", Map.of("patient_id", PATIENT_ID)), Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("name").asText()).isEqualTo(name);
        SpecSchemaValidator.assertValid(response.getBody(), SpecSchemaValidator.RESULT_SET);
    }

    /**
     * Exercises the schema's two conditional rules at once: a failing node must
     * carry {@code error}, and — since node_3 is not localized for this patient —
     * a never-asked endpoint must <em>omit</em> {@code latency_ms} rather than
     * report a zero that reads as "answered instantly" (§9.5, N40, finding F5).
     */
    @Test
    @Tag("CP-35")
    void erroredAndNeverAskedEndpointsStillProduceAConformantEnvelope() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1",
                "2026-01-01T10:00") + "]", 0);
        NODE_2.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(com.github.tomakehurst.wiremock.client.WireMock
                        .urlPathEqualTo("/v1/query/aql"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .aResponse().withStatus(500)));
        stubQuery(NODE_3, "[]", 0);

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY,
                Map.of("openEHR-federation-completeness", "partial"));
        JsonNode body = response.getBody();

        // Under the opted-in best-effort mode a partial answer is a successful
        // response with an incomplete-coverage marker, not an error (§11.4).
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        SpecSchemaValidator.assertValid(body, SpecSchemaValidator.RESULT_SET);

        for (JsonNode endpoint : body.get("meta").get("federation").get("endpoints")) {
            String status = endpoint.get("status").asText();
            if (List.of("excluded", "not-localized").contains(status)) {
                assertThat(endpoint.has("latency_ms"))
                        .as("a node reported '%s' was never asked, so was never timed", status)
                        .isFalse();
            }
        }
    }

    /** An empty result set is still a conformant RESULT_SET (§11.3). */
    @Test
    @Tag("CP-35")
    void emptyResultSetValidates() {
        stubQuery(NODE_1, "[]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("rows")).isEmpty();
        SpecSchemaValidator.assertValid(response.getBody(), SpecSchemaValidator.RESULT_SET);
    }

    @Test
    @Tag("CP-23")
    void optionsBodyValidatesAgainstThePublishedSelfDescriptionSchema() {
        ResponseEntity<JsonNode> response =
                exchangeJson(HttpMethod.OPTIONS, "/v1/", null, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        SpecSchemaValidator.assertValid(response.getBody(), SpecSchemaValidator.OPTIONS_ROOT);
    }

    /**
     * The two {@code status} vocabularies are genuinely different types, and the
     * schemas are where that becomes enforceable rather than merely asserted in a
     * comment (§7a.2 endpoint-membership-status). {@code not-localized} is a
     * per-query outcome and is meaningless as a membership status; the OPTIONS
     * schema rejects it outright.
     */
    @Test
    @Tag("CP-23")
    void membershipStatusIsNotThePerQueryVocabulary() {
        JsonNode body = exchangeJson(HttpMethod.OPTIONS, "/v1/", null, Map.of()).getBody();

        for (JsonNode endpoint : body.get("endpoints")) {
            assertThat(endpoint.get("status").asText())
                    .as("membership status, not the §11.1 per-query vocabulary")
                    .isNotIn("not-localized", "not-resolved", "time-out", "consent-denied");
        }
        SpecSchemaValidator.assertValid(body, SpecSchemaValidator.OPTIONS_ROOT);
    }
}
