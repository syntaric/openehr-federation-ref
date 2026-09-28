// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CP-40 / §12.7 / N44: the gateway-held stored-query registry, end to end.
 *
 * <p>Executes the stored-query half of spec §16.3 Track 9: a definition
 * {@code PUT} at the gateway is invocable by name and fans out across members,
 * the response carries the ITS-REST {@code name} member, and a repeat
 * {@code PUT} to the same {@code {name}/{version}} is refused. Definition
 * fan-out to nodes is not offered here, so its Track 9 clauses do not apply.
 *
 * <p>Every test stores under its own namespace: rows are immutable and the
 * Postgres is shared across the IT suite, so a fixed name would collide with
 * itself on the second run.
 */
@Tag("CP-40")
@Tag("TRACK-9")
class StoredQueryRegistryIT extends IntegrationTestBase {

    /** The definition under test: the patient through a parameter, never a literal. */
    private static final String DEFINITION =
            "SELECT c/uid/value AS composition_id, c/context/start_time/value AS start_time "
                    + "FROM EHR e CONTAINS COMPOSITION c "
                    + "WHERE e/ehr_status/subject/external_ref/id/value = $patient_id "
                    + "ORDER BY c/context/start_time/value";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String namespace;

    @BeforeEach
    void freshNamespace() {
        namespace = "it" + UUID.randomUUID().toString().replace("-", "");
    }

    private String name(final String local) {
        return namespace + "::" + local;
    }

    // ---- definition management ------------------------------------------------

    @Test
    @DisplayName("PUT as text/plain stores the definition; GET reads it back exactly, in both forms")
    void definitionIsStoredAsTextAndReadBack() {
        final String name = name("encounters");

        final ResponseEntity<JsonNode> stored = putText(name, "1.0.0", DEFINITION);

        assertThat(stored.getStatusCode().value()).isEqualTo(200);
        final JsonNode metadata = stored.getBody();
        assertThat(metadata.get("name").asText()).isEqualTo(name);
        assertThat(metadata.get("type").asText()).isEqualTo("AQL");
        assertThat(metadata.get("version").asText()).isEqualTo("1.0.0");
        assertThat(metadata.get("saved").asText()).isNotEmpty();
        assertThat(metadata.get("q").asText()).isEqualTo(DEFINITION);

        final ResponseEntity<JsonNode> exact = exchangeJson(HttpMethod.GET,
                "/v1/definition/query/" + name + "/1.0.0", null, Map.of());
        assertThat(exact.getStatusCode().value()).isEqualTo(200);
        assertThat(exact.getBody()).isEqualTo(metadata);

        final ResponseEntity<JsonNode> list = exchangeJson(HttpMethod.GET,
                "/v1/definition/query/" + name, null, Map.of());
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody().get("versions")).hasSize(1);
        assertThat(list.getBody().get("versions").get(0)).isEqualTo(metadata);
    }

    @Test
    @DisplayName("PUT as JSON {q} is the same definition")
    void definitionIsStoredAsJsonToo() {
        final String name = name("encounters-json");

        final ResponseEntity<JsonNode> stored = exchangeJson(HttpMethod.PUT,
                "/v1/definition/query/" + name + "/1.0.0", Map.of("q", DEFINITION), Map.of());

        assertThat(stored.getStatusCode().value()).isEqualTo(200);
        assertThat(stored.getBody().get("q").asText()).isEqualTo(DEFINITION);
    }

    @Test
    @DisplayName("a second PUT to the same name/version is 409 and leaves the stored text unchanged")
    void repeatPutIsRefusedAndTextUnchanged() {
        final String name = name("immutable");
        putText(name, "1.0.0", DEFINITION);

        final ResponseEntity<JsonNode> again = putText(name, "1.0.0", DEFINITION + " DESC LIMIT 1");

        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().get("error").asText()).isEqualTo("FED_STORED_QUERY_EXISTS");
        final ResponseEntity<JsonNode> readBack = exchangeJson(HttpMethod.GET,
                "/v1/definition/query/" + name + "/1.0.0", null, Map.of());
        assertThat(readBack.getBody().get("q").asText()).isEqualTo(DEFINITION);

        // …and a new version is how a definition changes
        assertThat(putText(name, "1.0.1", DEFINITION + " DESC").getStatusCode().value()).isEqualTo(200);
        assertThat(exchangeJson(HttpMethod.GET, "/v1/definition/query/" + name, null, Map.of())
                .getBody().get("versions")).hasSize(2);
    }

    @Test
    @DisplayName("storing a definition causes no node traffic — the gateway is the authority")
    void putCausesNoNodeTraffic() {
        putText(name("quiet"), "1.0.0", DEFINITION);

        for (final com.github.tomakehurst.wiremock.WireMockServer node : List.of(NODE_1, NODE_2, NODE_3)) {
            assertThat(node.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    @DisplayName("a malformed version, the reserved name and a non-AQL body are 400")
    void malformedDefinitionsAre400() {
        final ResponseEntity<JsonNode> badVersion = putText(name("v"), "1.0", DEFINITION);
        assertThat(badVersion.getStatusCode().value()).isEqualTo(400);
        assertThat(badVersion.getBody().get("error").asText()).isEqualTo("FED_STORED_QUERY_INVALID");

        final ResponseEntity<JsonNode> reserved = putText("aql", "1.0.0", DEFINITION);
        assertThat(reserved.getStatusCode().value()).isEqualTo(400);
        assertThat(reserved.getBody().get("error").asText()).isEqualTo("FED_STORED_QUERY_INVALID");

        final ResponseEntity<JsonNode> notAql = putText(name("junk"), "1.0.0", "this is not AQL");
        assertThat(notAql.getStatusCode().value()).isEqualTo(400);
        assertThat(notAql.getBody().get("error").asText()).isEqualTo("FED_AQL_INVALID");

        final ResponseEntity<JsonNode> wrongType = exchangeBytesAs(HttpMethod.PUT,
                "/v1/definition/query/" + name("xml") + "/1.0.0",
                "<q/>".getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/xml"));
        assertThat(wrongType.getStatusCode().value()).isEqualTo(415);
    }

    /**
     * N33 at rest: a definition outlives its request, so a literal patient
     * identifier in it would be a patient identifier persisted by the gateway.
     * The patient is named through a {@code $parameter}; the value comes per call.
     */
    @Test
    @Tag("CP-26")
    @DisplayName("a definition with a literal subject identifier is refused, never stored")
    void literalSubjectDefinitionIsRefused() {
        final String name = name("literal");

        final ResponseEntity<JsonNode> refused = putText(name, "1.0.0",
                DEFINITION.replace("$patient_id", "'" + PATIENT_ID + "'"));

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(refused.getBody().get("error").asText()).isEqualTo("FED_IDENTIFIER_HYGIENE");
        assertThat(refused.getBody().toString()).doesNotContain(PATIENT_ID);
        assertThat(exchangeJson(HttpMethod.GET, "/v1/definition/query/" + name, null, Map.of())
                .getStatusCode().value()).isEqualTo(404);

        // the ENTRY-level carrier (§5.4.3) is a patient carrier too
        final ResponseEntity<JsonNode> entryLevel = putText(name, "1.0.0",
                "SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c CONTAINS OBSERVATION o "
                        + "WHERE o/subject/identifiers/id = '" + PATIENT_ID + "'");
        assertThat(entryLevel.getBody().get("error").asText()).isEqualTo("FED_IDENTIFIER_HYGIENE");
    }

    // ---- invocation -------------------------------------------------------------

    @Test
    @DisplayName("POST by name expands into the ordinary fan-out and answers under that name")
    void storedQueryFansOutUnderItsName() {
        final String name = name("fanout");
        putText(name, "1.0.0", DEFINITION);
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[" + row("bbbbbbbb-2222-0000-0000-000000000001::cdr2.test::1", "2026-01-01T09:00") + "]", 0);
        stubQuery(NODE_3, "[]", 0);

        final ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.POST, "/v1/query/" + name,
                Map.of("query_parameters", Map.of("patient_id", PATIENT_ID)), Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        final JsonNode body = response.getBody();
        // rows from more than one member, merged and ordered at the tier
        assertThat(body.get("rows")).hasSize(2);
        assertThat(cell(body, 0, "start_time").asText()).isEqualTo("2026-01-01T09:00");
        // the ITS-REST name member names the gateway's definition (§12.7)
        assertThat(body.get("name").asText()).isEqualTo(name);
        // q echoes the stored text with the parameter unbound — the value is never echoed
        assertThat(body.get("q").asText()).isEqualTo(DEFINITION);
        // the envelope covers every member, as for inline AQL
        final List<String> ids = new ArrayList<>();
        body.get("meta").get("federation").get("endpoints").forEach(e -> ids.add(e.get("id").asText()));
        assertThat(ids).containsExactlyInAnyOrder("node_1", "node_2", "node_3");
        assertThat(body.get("meta").get("federation").get("complete").asBoolean()).isTrue();
        assertThat(response.getHeaders().getFirst("openEHR-federation-endpoint")).contains("node_1");

        // each node saw its own ehr_id, and neither the identifier nor the parameter
        NODE_1.verify(postRequestedFor(urlPathEqualTo("/v1/query/aql"))
                .withRequestBody(containing(EHR_NODE1))
                .withRequestBody(notMatching(".*" + PATIENT_ID + ".*"))
                .withRequestBody(notMatching(".*\\$patient_id.*")));
        NODE_2.verify(postRequestedFor(urlPathEqualTo("/v1/query/aql"))
                .withRequestBody(containing(EHR_NODE2))
                .withRequestBody(notMatching(".*" + PATIENT_ID + ".*")));
    }

    @Test
    @DisplayName("the GET form binds query-string parameters and resolves an explicit version")
    void getFormBindsQueryStringParameters() {
        final String name = name("getform");
        putText(name, "2.0.0", DEFINITION);
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);

        final ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.GET,
                "/v1/query/" + name + "/2.0.0?patient_id=" + PATIENT_ID, null, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("rows")).hasSize(1);
        assertThat(response.getBody().get("name").asText()).isEqualTo(name);
        NODE_1.verify(postRequestedFor(urlPathEqualTo("/v1/query/aql"))
                .withRequestBody(containing(EHR_NODE1))
                .withRequestBody(notMatching(".*" + PATIENT_ID + ".*")));

        final ResponseEntity<JsonNode> repeated = exchangeJson(HttpMethod.GET,
                "/v1/query/" + name + "/2.0.0?patient_id=" + PATIENT_ID + "&patient_id=x", null, Map.of());
        assertThat(repeated.getStatusCode().value()).isEqualTo(400);
        assertThat(repeated.getBody().get("error").asText()).isEqualTo("FED_QUERY_PARAMETER_INVALID");
    }

    @Test
    @DisplayName("no version resolves the latest, numerically")
    void latestVersionIsResolvedWhenNoneGiven() {
        final String name = name("latest");
        putText(name, "1.2.0", DEFINITION);
        putText(name, "1.10.0", DEFINITION + " DESC");
        putText(name, "1.9.0", DEFINITION);
        stubQuery(NODE_1, "[]", 0);
        stubQuery(NODE_2, "[]", 0);
        stubQuery(NODE_3, "[]", 0);

        final ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.POST, "/v1/query/" + name,
                Map.of("query_parameters", Map.of("patient_id", PATIENT_ID)), Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("q").asText()).isEqualTo(DEFINITION + " DESC");
    }

    @Test
    @DisplayName("an unknown name or version is 404")
    void unknownStoredQueryIs404() {
        final ResponseEntity<JsonNode> unknownName = exchangeJson(HttpMethod.POST, "/v1/query/" + name("nope"),
                Map.of("query_parameters", Map.of("patient_id", PATIENT_ID)), Map.of());
        assertThat(unknownName.getStatusCode().value()).isEqualTo(404);
        assertThat(unknownName.getBody().get("error").asText()).isEqualTo("FED_NOT_FOUND");

        putText(name("one"), "1.0.0", DEFINITION);
        final ResponseEntity<JsonNode> unknownVersion = exchangeJson(HttpMethod.POST,
                "/v1/query/" + name("one") + "/9.9.9",
                Map.of("query_parameters", Map.of("patient_id", PATIENT_ID)), Map.of());
        assertThat(unknownVersion.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("unbound and unknown parameters are 400, named but never valued")
    void unboundAndUnknownParametersAre400() {
        final String name = name("params");
        putText(name, "1.0.0", DEFINITION);

        final ResponseEntity<JsonNode> unbound = exchangeJson(HttpMethod.POST, "/v1/query/" + name,
                Map.of("query_parameters", Map.of()), Map.of());
        assertThat(unbound.getStatusCode().value()).isEqualTo(400);
        assertThat(unbound.getBody().get("error").asText()).isEqualTo("FED_QUERY_PARAMETER_INVALID");
        assertThat(unbound.getBody().get("details").get("unbound").get(0).asText()).isEqualTo("patient_id");

        final ResponseEntity<JsonNode> unknown = exchangeJson(HttpMethod.POST, "/v1/query/" + name,
                Map.of("query_parameters", Map.of("patient_id", PATIENT_ID, "extra", "x")), Map.of());
        assertThat(unknown.getStatusCode().value()).isEqualTo(400);
        assertThat(unknown.getBody().get("details").get("unknown").get(0).asText()).isEqualTo("extra");
        assertThat(unknown.getBody().toString()).doesNotContain(PATIENT_ID);

        // no body at all: the parameter is still unbound, and the answer is still 400 not 500
        final ResponseEntity<JsonNode> noBody = exchangeJson(HttpMethod.POST, "/v1/query/" + name, null, Map.of());
        assertThat(noBody.getStatusCode().value()).isEqualTo(400);
        for (final com.github.tomakehurst.wiremock.WireMockServer node : List.of(NODE_1, NODE_2, NODE_3)) {
            assertThat(node.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    @DisplayName("fetch is a tier LIMIT; offset is governed by the same N39 policy as an inline OFFSET")
    void fetchLimitsAndOffsetIsRejected() {
        final String name = name("paging");
        putText(name, "1.0.0", DEFINITION);
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1", "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[" + row("bbbbbbbb-2222-0000-0000-000000000001::cdr2.test::1", "2026-01-01T11:00") + "]", 0);
        stubQuery(NODE_3, "[]", 0);

        final ResponseEntity<JsonNode> fetched = exchangeJson(HttpMethod.POST, "/v1/query/" + name,
                Map.of("query_parameters", Map.of("patient_id", PATIENT_ID), "fetch", 1), Map.of());
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody().get("rows")).hasSize(1);
        NODE_1.verify(postRequestedFor(urlPathEqualTo("/v1/query/aql")).withRequestBody(containing("LIMIT 1")));

        final ResponseEntity<JsonNode> offset = exchangeJson(HttpMethod.POST, "/v1/query/" + name,
                Map.of("query_parameters", Map.of("patient_id", PATIENT_ID), "offset", 5), Map.of());
        assertThat(offset.getStatusCode().value()).isEqualTo(400);
        assertThat(offset.getBody().get("error").asText()).isEqualTo("FED_OFFSET_UNSUPPORTED");
    }

    // ---- helpers ------------------------------------------------------------------

    private ResponseEntity<JsonNode> putText(final String name, final String version, final String aql) {
        return exchangeBytesAs(HttpMethod.PUT, "/v1/definition/query/" + name + "/" + version,
                aql.getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "text/plain"));
    }

    private ResponseEntity<JsonNode> exchangeBytesAs(final HttpMethod method, final String path,
                                                     final byte[] body, final Map<String, String> headers) {
        final ResponseEntity<byte[]> raw = exchangeBytes(method, path, body, headers);
        try {
            final JsonNode node = raw.getBody() == null || raw.getBody().length == 0
                    ? objectMapper.nullNode() : objectMapper.readTree(raw.getBody());
            return new ResponseEntity<>(node, raw.getHeaders(), raw.getStatusCode());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
