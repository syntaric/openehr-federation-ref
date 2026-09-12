// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan M4/M5/M8 scenario matrix over three WireMock CDRs: merge semantics,
 * partial results, timeouts, targeting, dedup and identifier hygiene as seen
 * from the node side.
 *
 * <p>Executes spec §16.3 Track 1 (an unmodified client gets a single-CDR-shaped
 * result), Track 3 (the endpoint directive selects the node set) and Track 4
 * (unresponsive and not-resolved nodes surface in {@code meta.endpoints[]} and
 * "found nowhere" is 200 + empty rows).
 *
 * <p>Track 4 is executed under the best-effort reading of §11.4/N37 — a node
 * that cannot answer is reported rather than failing the query. Note that
 * §11.2's status table contradicts this reading; the ambiguity has been raised
 * with the specification authors, and this implementation follows §11.4
 * because the alternative turns one slow node into a failed federation.
 */
@Tag("TRACK-1")
@Tag("TRACK-3")
@Tag("TRACK-4")
class FederatedQueryIT extends IntegrationTestBase {

    private static final String SUBJECT_QUERY =
            "SELECT c/uid/value AS composition_id, c/context/start_time/value AS start_time "
                    + "FROM EHR e CONTAINS COMPOSITION c "
                    + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "' "
                    + "ORDER BY c/context/start_time/value";

    private ResponseEntity<JsonNode> query(String aql, Map<String, String> headers) {
        return exchangeJson(HttpMethod.POST, "/v1/query/aql", Map.of("q", aql), headers);
    }

    @Test
    @Tag("CP-1")
    @Tag("CP-4")
    @Tag("CP-11")
    @Tag("CP-30")
    void fanOutMergesRowsAndReportsEveryEndpoint() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00")
                + "," + row("aaaaaaaa-1111-0000-0000-000000000002::cdr1.test::1", "2026-01-01T12:00") + "]", 0);
        stubQuery(NODE_2, "[" + row("bbbbbbbb-2222-0000-0000-000000000001::cdr2.test::1", "2026-01-01T11:00") + "]", 0);
        stubQuery(NODE_3, "[" + row("cccccccc-3333-0000-0000-000000000001::cdr3.test::1", "2026-01-01T09:00") + "]", 0);

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("rows")).hasSize(4);
        assertThat(body.get("meta").get("complete").asBoolean()).isTrue();
        assertThat(body.get("meta").get("endpoints")).hasSize(3);
        body.get("meta").get("endpoints").forEach(e -> {
            assertThat(e.get("status").asText()).isEqualTo("active");
            assertThat(e.get("latency_ms").isNumber()).isTrue();
            assertThat(e.get("row_count").isNumber()).isTrue();
        });
        // tier re-applied ORDER BY across the merge
        List<String> times = new ArrayList<>();
        body.get("rows").forEach(r -> times.add(cell(body, r, "start_time").asText()));
        assertThat(times).isSorted();
    }

    @Test
    @Tag("CP-26")
    @Tag("CP-3")
    void nodesReceiveEhrIdScopedAqlAndNeverThePatientIdentifier() {
        stubQuery(NODE_1, "[]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);

        query(SUBJECT_QUERY, Map.of());

        NODE_1.verify(postRequestedFor(urlPathEqualTo("/v1/query/aql"))
                .withRequestBody(containing(EHR_NODE1))
                .withRequestBody(notMatching(".*" + PATIENT_ID + ".*")));
        NODE_2.verify(postRequestedFor(urlPathEqualTo("/v1/query/aql"))
                .withRequestBody(containing(EHR_NODE2))
                .withRequestBody(notMatching(".*" + PATIENT_ID + ".*")));
        // headers/path capture: no request anywhere on the node carried the identifier
        NODE_1.getAllServeEvents().forEach(event -> {
            assertThat(event.getRequest().getUrl()).doesNotContain(PATIENT_ID);
            event.getRequest().getHeaders().all().forEach(h ->
                    assertThat(String.join(",", h.values())).doesNotContain(PATIENT_ID));
        });
    }

    @Test
    @Tag("CP-31")
    void slowNodeIsAbandonedAtPerNodeTimeoutAndMarkedTimeout() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[" + row("bbbbbbbb-2222-0000-0000-000000000001::cdr2.test::1", "2026-01-01T11:00") + "]", 3000);
        stubQuery(NODE_3, "[]", 0);

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode meta = response.getBody().get("meta");
        assertThat(meta.get("complete").asBoolean()).isFalse();
        JsonNode node2 = endpointMeta(meta, "node_2");
        assertThat(node2.get("status").asText()).isEqualTo("time-out");
        assertThat(node2.get("error").asText()).isNotEmpty();
        // the slow node's rows were discarded, the healthy ones kept
        assertThat(response.getBody().get("rows")).hasSize(1);
    }

    @Test
    @Tag("CP-11")
    @Tag("CP-30")
    void failingNodeContributesNoRowsButStaysVisible() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[]", 0);
        NODE_3.stubFor(WireMock.post(urlPathEqualTo("/v1/query/aql"))
                .willReturn(WireMock.aResponse().withStatus(500)));

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode meta = response.getBody().get("meta");
        assertThat(meta.get("complete").asBoolean()).isFalse();
        assertThat(endpointMeta(meta, "node_3").get("status").asText()).isEqualTo("offline");
        assertThat(endpointMeta(meta, "node_3").get("error").asText()).contains("500");
        assertThat(response.getBody().get("rows")).hasSize(1);
    }

    @Test
    @Tag("CP-30")
    void allOrNothingCompletenessFailsTheWholeQuery() {
        stubQuery(NODE_1, "[]", 0);
        stubQuery(NODE_2, "[]", 3000);
        stubQuery(NODE_3, "[]", 0);

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY,
                Map.of("openEHR-federation-completeness", "all"));

        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_INCOMPLETE");
    }

    @Test
    @Tag("CP-12")
    void patientFoundNowhereIsAnEmpty200NotA404() {
        ResponseEntity<JsonNode> response = query(
                SUBJECT_QUERY.replace(PATIENT_ID, "0000000000"), Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("rows")).isEmpty();
        body.get("meta").get("endpoints").forEach(e ->
                assertThat(e.get("status").asText()).isEqualTo("not-resolved"));
    }

    @Test
    @Tag("CP-28")
    void conflictingDirectiveAndHeaderNodeSetsAreRejectedNamingBothSets() {
        String directed = "SELECT c/uid/value AS composition_id "
                + "FROM ENDPOINT p [ \"node_1\" ] CONTAINS EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'";

        ResponseEntity<JsonNode> response = query(directed,
                Map.of("openEHR-federation-endpoint", "node_2"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_TARGET_CONFLICT");
        String details = response.getBody().get("details").toString();
        assertThat(details).contains("node_1").contains("node_2");
    }

    @Test
    @Tag("CP-6")
    @Tag("CP-37")
    void directedQueryAnnotatesRowsWithEndpointProvenance() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[" + row("bbbbbbbb-2222-0000-0000-000000000001::cdr2.test::1", "2026-01-01T11:00") + "]", 0);

        String directed = "SELECT p/id AS endpoint_id, p/system_id AS sys, "
                + "c/uid/value AS composition_id, c/context/start_time/value AS start_time "
                + "FROM ENDPOINT p [ \"node_1\", \"node_2\" ] CONTAINS EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'";

        ResponseEntity<JsonNode> response = query(directed, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("meta").get("endpoints")).hasSize(2);
        assertThat(body.get("rows")).hasSize(2);
        // The ENDPOINT projection led the SELECT list, so it is columns[0] — and
        // with positional rows (§9.1) that is element 0 of every row.
        assertThat(body.get("columns").get(0).get("name").asText()).isEqualTo("endpoint_id");
        body.get("rows").forEach(r -> {
            assertThat(r.isArray()).as("ITS-REST ResultSetRow is an array, not an object").isTrue();
            String endpointId = cell(body, r, "endpoint_id").asText();
            assertThat(endpointId).isIn("node_1", "node_2");
            assertThat(cell(body, r, "sys").asText())
                    .isEqualTo(endpointId.equals("node_1") ? "cdr1.test" : "cdr2.test");
        });
        // node_3 was deliberately excluded, not asked
        assertThat(NODE_3.getAllServeEvents()).isEmpty();
    }

    @Test
    @Tag("CP-28")
    void unknownEndpointIsRejectedInEitherMechanism() {
        ResponseEntity<JsonNode> viaHeader = query(SUBJECT_QUERY,
                Map.of("openEHR-federation-endpoint", "node_99"));
        assertThat(viaHeader.getStatusCode().value()).isEqualTo(400);
        assertThat(viaHeader.getBody().get("error").asText()).isEqualTo("FED_UNKNOWN_TARGET");

        ResponseEntity<JsonNode> viaDirective = query(
                "SELECT c/uid/value FROM ENDPOINT p [ \"node_99\" ] CONTAINS EHR e "
                        + "CONTAINS COMPOSITION c "
                        + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'",
                Map.of());
        assertThat(viaDirective.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @Tag("CP-32")
    void orderByWithLimitReturnsTheGlobalTopN() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00")
                + "," + row("aaaaaaaa-1111-0000-0000-000000000002::cdr1.test::1", "2026-01-01T12:00") + "]", 0);
        stubQuery(NODE_2, "[" + row("bbbbbbbb-2222-0000-0000-000000000001::cdr2.test::1", "2026-01-01T13:00")
                + "," + row("bbbbbbbb-2222-0000-0000-000000000002::cdr2.test::1", "2026-01-01T09:00") + "]", 0);
        stubQuery(NODE_3, "[]", 0);

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY + " DESC LIMIT 2", Map.of());

        JsonNode body = response.getBody();
        assertThat(body.get("rows")).hasSize(2);
        assertThat(cell(body, 0, "start_time").asText()).isEqualTo("2026-01-01T13:00");
        assertThat(cell(body, 1, "start_time").asText()).isEqualTo("2026-01-01T12:00");
    }

    /**
     * ORDER BY must survive a node that reports column paths without the FROM
     * alias. {@link IntegrationTestBase#stubQuery} models EHRbase, which echoes
     * the alias ({@code c/uid/value}); FerroEHR strips it ({@code /uid/value}),
     * verified against the demo stack. Every other ORDER BY test here stubs only
     * the EHRbase shape, so this is the one that would have caught the bug.
     */
    @Test
    @Tag("CP-32")
    void orderByResolvesWhenNodesReportAliasStrippedColumnPaths() {
        stubQueryWithUnprefixedColumns(NODE_1,
                "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00")
                        + "," + row("aaaaaaaa-1111-0000-0000-000000000002::cdr1.test::1", "2026-01-01T12:00") + "]");
        stubQueryWithUnprefixedColumns(NODE_2,
                "[" + row("bbbbbbbb-2222-0000-0000-000000000001::cdr2.test::1", "2026-01-01T13:00") + "]");
        stubQueryWithUnprefixedColumns(NODE_3, "[]");

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY + " DESC LIMIT 2", Map.of());

        assertThat(response.getStatusCode().value())
                .as("alias-stripped column paths must not be rejected as unselected")
                .isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.get("rows")).hasSize(2);
        assertThat(cell(body, 0, "start_time").asText()).isEqualTo("2026-01-01T13:00");
        assertThat(cell(body, 1, "start_time").asText()).isEqualTo("2026-01-01T12:00");
    }

    /**
     * The façade's own {@code columns[].path} must not depend on which vendor
     * answered. EHRbase and FerroEHR report the same column differently
     * ({@code c/uid/value} vs {@code /uid/value}), so echoing the node's string
     * made façade output vendor-specific — and, with nodes answering in
     * whatever order they finish, effectively nondeterministic. Paths are now
     * rendered from the façade's SELECT list, so both stubs yield one answer.
     */
    @Test
    @Tag("CP-8")
    @Tag("CP-35")
    void envelopeColumnPathsAreFacadeRenderedNotVendorEchoed() {
        String uid = "aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1";

        stubQuery(NODE_1, "[" + row(uid, "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);
        JsonNode viaEhrbaseShape = query(SUBJECT_QUERY, Map.of()).getBody().get("columns");

        stubQueryWithUnprefixedColumns(NODE_1, "[" + row(uid, "2026-01-01T10:00") + "]");
        stubQueryWithUnprefixedColumns(NODE_2, "[]");
        stubQueryWithUnprefixedColumns(NODE_3, "[]");
        JsonNode viaFerroehrShape = query(SUBJECT_QUERY, Map.of()).getBody().get("columns");

        assertThat(viaFerroehrShape)
                .as("envelope columns must be identical whichever vendor shape answered")
                .isEqualTo(viaEhrbaseShape);

        // …and the one answer is the façade's own rendering, alias included
        List<String> paths = new ArrayList<>();
        viaEhrbaseShape.forEach(c -> paths.add(c.get("path").asText()));
        assertThat(paths).containsExactly("c/uid/value", "c/context/start_time/value");
    }

    /** A FerroEHR-shaped result set: same columns, no FROM alias on the paths. */
    private static void stubQueryWithUnprefixedColumns(WireMockServer node, String rowsJson) {
        node.stubFor(WireMock.post(urlPathEqualTo("/v1/query/aql"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"q":"dispatched",
                                 "columns":[{"name":"composition_id","path":"/uid/value"},
                                            {"name":"start_time","path":"/context/start_time/value"}],
                                 "rows":%s}
                                """.formatted(rowsJson))));
    }

    @Test
    @Tag("CP-32")
    void offsetIsRejectedNeverSilentlyPushedDown() {
        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY + " LIMIT 10 OFFSET 5", Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_OFFSET_UNSUPPORTED");
        assertThat(response.getBody().get("message").asText()).contains("not supported");
    }

    @Test
    @Tag("CP-10")
    void undirectedAggregateIsRejectedWithAReason() {
        ResponseEntity<JsonNode> response = query(
                "SELECT COUNT(c/uid/value) AS n FROM EHR e CONTAINS COMPOSITION c "
                        + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'",
                Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_AGGREGATE_UNSUPPORTED");
        assertThat(response.getBody().get("message").asText())
                .contains("cannot be computed correctly");
    }

    @Test
    @Tag("CP-10")
    void directedSingleNodeAggregateIsDispatchedUnchanged() {
        NODE_1.stubFor(WireMock.post(urlPathEqualTo("/v1/query/aql"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"columns\":[{\"name\":\"n\",\"path\":\"COUNT\"}],\"rows\":[[7]]}")));

        ResponseEntity<JsonNode> response = query(
                "SELECT COUNT(c/uid/value) AS n FROM EHR e CONTAINS COMPOSITION c "
                        + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'",
                Map.of("openEHR-federation-endpoint", "node_1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(cell(response.getBody(), 0, "n").asInt()).isEqualTo(7);
    }

    @Test
    @Tag("CP-9")
    @Tag("CP-29")
    void versionIdentityDedupKeepsOriginAndRecordsSuppression() {
        String sharedUid = "dddddddd-0000-0000-0000-000000000001::cdr1.test::1";
        stubQuery(NODE_1, "[" + row(sharedUid, "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[" + row(sharedUid, "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_3, "[]", 0);

        // default: pass-through, duplicates kept
        ResponseEntity<JsonNode> plain = query(SUBJECT_QUERY, Map.of());
        assertThat(plain.getBody().get("rows")).hasSize(2);
        assertThat(plain.getBody().get("meta").get("dedup")).isNull();

        // opt-in: originating copy kept, suppression visible
        stubQuery(NODE_1, "[" + row(sharedUid, "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[" + row(sharedUid, "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_3, "[]", 0);
        ResponseEntity<JsonNode> deduped = query(SUBJECT_QUERY,
                Map.of("openEHR-federation-dedup", "version-identity"));
        assertThat(deduped.getBody().get("rows")).hasSize(1);
        JsonNode dedup = deduped.getBody().get("meta").get("dedup");
        assertThat(dedup.get("mode").asText()).isEqualTo("version-identity");
        assertThat(dedup.get("suppressed")).hasSize(1);
        assertThat(dedup.get("suppressed").get(0).get("endpoint_id").asText()).isEqualTo("node_2");
        // row_count keeps the pre-dedup contribution auditable
        assertThat(endpointMeta(deduped.getBody().get("meta"), "node_2")
                .get("row_count").asInt()).isEqualTo(1);
    }

    @Test
    @Tag("CP-31")
    void preferWaitShortensTheBudgetAndIsReportedInMeta() {
        stubQuery(NODE_1, "[]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);

        ResponseEntity<JsonNode> shortened = query(SUBJECT_QUERY, Map.of("Prefer", "wait=1"));
        assertThat(shortened.getBody().get("meta").get("timeout")
                .get("effective_overall_ms").asLong()).isEqualTo(1000);

        ResponseEntity<JsonNode> extended = query(SUBJECT_QUERY, Map.of("Prefer", "wait=60"));
        assertThat(extended.getBody().get("meta").get("timeout")
                .get("effective_overall_ms").asLong()).isEqualTo(2500);
        assertThat(extended.getBody().get("meta").get("timeout")
                .get("overall_ms").asLong()).isEqualTo(2500);
    }

    @Test
    @Tag("CP-6")
    void organisationHeaderExpandsToItsEndpoints() {
        stubQuery(NODE_3, "[" + row("cccccccc-3333-0000-0000-000000000001::cdr3.test::1", "2026-01-01T09:00") + "]", 0);

        ResponseEntity<JsonNode> response = query(SUBJECT_QUERY,
                Map.of("openEHR-federation-organisation", "org-b"));

        JsonNode endpoints = response.getBody().get("meta").get("endpoints");
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).get("id").asText()).isEqualTo("node_3");
        assertThat(NODE_1.getAllServeEvents()).isEmpty();
    }

    @Test
    @Tag("CP-7")
    void selectedSubjectColumnIsReinjectedFromTheResolutionInput() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);

        String withProjection = "SELECT e/ehr_status/subject/external_ref/id/value AS patient_id, "
                + "c/uid/value AS composition_id, c/context/start_time/value AS start_time "
                + "FROM EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'";

        ResponseEntity<JsonNode> response = query(withProjection, Map.of());

        JsonNode body = response.getBody();
        assertThat(cell(body, 0, "patient_id").asText()).isEqualTo(PATIENT_ID);
        // The subject projection was re-injected at position 0 of the façade's
        // SELECT list, so it leads columns[] — and therefore leads every row.
        assertThat(body.get("columns").get(0).get("name").asText()).isEqualTo("patient_id");
        assertThat(body.get("rows").get(0).get(0).asText()).isEqualTo(PATIENT_ID);
        // …and the node never saw it (CP-2/CP-7: value re-injected, never read from a CDR)
        NODE_1.verify(postRequestedFor(urlPathEqualTo("/v1/query/aql"))
                .withRequestBody(notMatching(".*" + PATIENT_ID + ".*")));
    }

    private JsonNode endpointMeta(JsonNode meta, String endpointId) {
        for (JsonNode e : meta.get("endpoints")) {
            if (endpointId.equals(e.get("id").asText())) {
                return e;
            }
        }
        throw new AssertionError("endpoint " + endpointId + " not in meta");
    }
}
