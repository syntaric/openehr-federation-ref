// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Shared integration harness (plan verification setup): Testcontainers
 * Postgres + three WireMock member CDRs — node_1 behaves, node_2 is the slow
 * one, node_3 is the failing one.
 *
 * <p>Registry rows are inserted through {@link RegistryFixture} rather than
 * declared as {@code federation.registry.*} properties: YAML seeding was deleted in M1
 * (plan 3 §A2), so the fixtures now go in through the same door the admin API
 * uses. Inbound security is disabled here; identity resolution is the static
 * dev/test resolver.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    protected static final String PATIENT_ID = "9999031234";
    protected static final String EHR_NODE1 = "11111111-1111-1111-1111-111111111111";
    protected static final String EHR_NODE2 = "22222222-2222-2222-2222-222222222222";
    protected static final String EHR_NODE3 = "33333333-3333-3333-3333-333333333333";

    protected static final PostgreSQLContainer<?> POSTGRES;
    protected static final WireMockServer NODE_1;
    protected static final WireMockServer NODE_2;
    protected static final WireMockServer NODE_3;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start();
        NODE_1 = new WireMockServer(wireMockConfig().dynamicPort());
        NODE_2 = new WireMockServer(wireMockConfig().dynamicPort());
        NODE_3 = new WireMockServer(wireMockConfig().dynamicPort());
        NODE_1.start();
        NODE_2.start();
        NODE_3.start();
    }

    @DynamicPropertySource
    static void federationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Without a key, storing an outbound credential fails — which is the
        // point of the boot-time validation, and means every deployment that
        // uses the smart/oauth2 profiles must set one. A fixed test value keeps
        // ciphertext stable across the ITs that assert on it.
        registry.add("federation.security.registry-secret-key",
                () -> "dW5pby10ZXN0LXJlZ2lzdHJ5LWtleS0zMi1ieXRlcyE=");
        registry.add("federation.timeouts.per-node", () -> "1500ms");
        registry.add("federation.timeouts.overall-budget", () -> "2500ms");
        registry.add("federation.timeouts.connect", () -> "500ms");

        registry.add("federation.identity.mode", () -> "static");
        registry.add("federation.identity.static-mappings." + PATIENT_ID + ".node_1", () -> EHR_NODE1);
        registry.add("federation.identity.static-mappings." + PATIENT_ID + ".node_2", () -> EHR_NODE2);
        registry.add("federation.identity.static-mappings." + PATIENT_ID + ".node_3", () -> EHR_NODE3);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected RegistryFixture fixture;

    /**
     * The three-node federation every IT below assumes.
     *
     * <p>Runs per test rather than once per class: the ITs share a Spring context
     * but each gets a fresh expectation of what the registry contains, and a test
     * that edits or deletes a row (M1 adds several) must not leave the next one
     * running against a registry it did not set up. The inserts are upserts, so
     * repeating them is cheap and idempotent.
     */
    @BeforeEach
    void seedRegistry() {
        fixture.organisation("org-a", "Org A");
        fixture.organisation("org-b", "Org B");
        fixture.source("org-a", "node_1", "cdr1.test", NODE_1.baseUrl(), "active", "WireMock CDR", "1.0");
        fixture.source("org-a", "node_2", "cdr2.test", NODE_2.baseUrl());
        fixture.source("org-b", "node_3", "cdr3.test", NODE_3.baseUrl());
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Issues an HTTP call against the gateway; never throws on error statuses. */
    protected ResponseEntity<JsonNode> exchangeJson(HttpMethod method, String path,
                                                    Object body, Map<String, String> headers) {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
        RestClient.RequestBodySpec spec = client.method(method).uri(path);
        headers.forEach(spec::header);
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            JsonNode node = bytes.length == 0 ? objectMapper.nullNode() : objectMapper.readTree(bytes);
            return new ResponseEntity<>(node, response.getHeaders(), response.getStatusCode());
        });
    }

    /** Raw-bytes variant for byte-identity assertions. */
    protected ResponseEntity<byte[]> exchangeBytes(HttpMethod method, String path,
                                                   byte[] body, Map<String, String> headers) {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
        RestClient.RequestBodySpec spec = client.method(method).uri(path);
        headers.forEach(spec::header);
        if (body != null) {
            spec.body(body);
        }
        return spec.exchange((request, response) -> new ResponseEntity<>(
                response.getBody().readAllBytes(), response.getHeaders(), response.getStatusCode()));
    }

    @BeforeEach
    void resetNodes() {
        NODE_1.resetAll();
        NODE_2.resetAll();
        NODE_3.resetAll();
    }

    /** Stubs a node's /v1/query/aql with an openEHR result set (rows as arrays). */
    protected static void stubQuery(WireMockServer node, String rowsJson, int delayMs) {
        node.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/query/aql"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(delayMs)
                        .withBody("""
                                {"q":"dispatched",
                                 "columns":[{"name":"composition_id","path":"c/uid/value"},
                                            {"name":"start_time","path":"c/context/start_time/value"}],
                                 "rows":%s}
                                """.formatted(rowsJson))));
    }

    protected static String row(String uid, String startTime) {
        return "[\"" + uid + "\",\"" + startTime + "\"]";
    }

    // ---- positional rows (ITS-REST ResultSetRow, spec §9.1/§9.4) -------------
    //
    // Federated rows are ordered ARRAYS, so a test cannot read a value by name
    // off the row itself: columns[] is the only thing that names them. These
    // helpers do the name→index lookup once, so a test still reads as "the value
    // of start_time in this row" rather than as an index nobody can check.
    //
    // Doing the lookup rather than hard-coding an index is deliberate: an index
    // literal would keep passing even if the gateway transposed two columns,
    // which is exactly the class of defect positional rows introduce.

    /** Index of {@code columnName} in the envelope's {@code columns[]}. */
    protected static int columnIndex(JsonNode body, String columnName) {
        JsonNode columns = body.get("columns");
        for (int i = 0; i < columns.size(); i++) {
            if (columnName.equals(columns.get(i).get("name").asText())) {
                return i;
            }
        }
        throw new AssertionError("no column named '" + columnName + "' in " + columns);
    }

    /** The value of {@code columnName} in {@code row}, resolved through {@code columns[]}. */
    protected static JsonNode cell(JsonNode body, JsonNode row, String columnName) {
        return row.get(columnIndex(body, columnName));
    }

    /** The value of {@code columnName} in row {@code rowIndex}. */
    protected static JsonNode cell(JsonNode body, int rowIndex, String columnName) {
        return cell(body, body.get("rows").get(rowIndex), columnName);
    }
}
