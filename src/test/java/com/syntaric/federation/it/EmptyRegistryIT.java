// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A federation with no members configured is <b>healthy but empty</b>, not broken
 * (plan 3 §E3, plan 4 M0).
 *
 * <p>This is the first thing an operator sees on a brand-new deployment, and it is
 * what the SPA's {@code .empty-first} state renders against. It matters beyond M0:
 * M1 removes YAML seeding (§A2), at which point an empty registry stops being an
 * edge case and becomes the default first-run experience.
 *
 * <p>Runs against its own Postgres with <b>no</b> {@code federation.registry.*} seed, so
 * the tables really are empty rather than emptied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("CP-23")
class EmptyRegistryIT {

    private static final PostgreSQLContainer<?> EMPTY_DB;

    static {
        EMPTY_DB = new PostgreSQLContainer<>("postgres:16-alpine");
        EMPTY_DB.start();
    }

    @DynamicPropertySource
    static void emptyRegistry(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EMPTY_DB::getJdbcUrl);
        registry.add("spring.datasource.username", EMPTY_DB::getUsername);
        registry.add("spring.datasource.password", EMPTY_DB::getPassword);
        registry.add("federation.identity.mode", () -> "static");
        // Deliberately no federation.registry.* seed: this is a first-run deployment.
    }

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("a federated query returns 200 with an empty result set, not an error")
    void queryOnAnEmptyFederationSucceeds() {
        JsonNode body = post("/v1/query/aql", Map.of(), Map.of(
                "q", "SELECT c/uid/value AS composition_id FROM EHR e CONTAINS COMPOSITION c "
                        + "WHERE e/ehr_status/subject/external_ref/id/value = '9999031234'"));

        assertThat(body.path("rows").isArray()).isTrue();
        assertThat(body.path("rows")).isEmpty();
        assertThat(body.path("meta").path("endpoints").isArray()).isTrue();
        assertThat(body.path("meta").path("endpoints")).isEmpty();
    }

    @Test
    @DisplayName("completeness: all behaves the same way — nothing to be incomplete about")
    void completenessAllOnAnEmptyFederationSucceeds() {
        JsonNode body = post("/v1/query/aql",
                Map.of("openEHR-federation-completeness", "all"),
                Map.of("q", "SELECT c/uid/value AS composition_id FROM EHR e CONTAINS COMPOSITION c "
                        + "WHERE e/ehr_status/subject/external_ref/id/value = '9999031234'"));

        assertThat(body.path("rows")).isEmpty();
        assertThat(body.path("meta").path("endpoints")).isEmpty();
    }

    @Test
    @DisplayName("OPTIONS / is a valid self-description with an empty member list")
    void selfDescriptionHasAnEmptyMemberList() {
        ResponseEntity<JsonNode> response = exchange(HttpMethod.OPTIONS, "/", Map.of(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.path("federation").path("id").asText()).isEqualTo("reference-federation");
        assertThat(body.path("endpoints").isArray()).isTrue();
        assertThat(body.path("endpoints")).isEmpty();
    }

    private JsonNode post(String path, Map<String, String> headers, Object body) {
        ResponseEntity<JsonNode> response = exchange(HttpMethod.POST, path, headers, body);
        assertThat(response.getStatusCode().value())
                .as("an empty federation is healthy, not an error")
                .isEqualTo(200);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path,
                                              Map<String, String> headers, Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build()
                .method(method)
                .uri(path);
        headers.forEach(spec::header);
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new ResponseEntity<>(
                    bytes.length == 0 ? objectMapper.nullNode() : objectMapper.readTree(bytes),
                    response.getHeaders(), response.getStatusCode());
        }, false);
    }
}
