// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.syntaric.federation.registry.SystemIdMappingRepository;

import java.util.Map;

/**
 * Plan M2 (decision #3): the /v1/ehr proxy must be byte-identical in both
 * directions, stamp the federation provenance headers, pass Location/ETag
 * through untouched, and learn creating_system_ids from what came back.
 *
 * <p>Executes spec §16.3 Track 9 (an unmodified client configured only with the
 * base URL reads and writes through the gateway; no path prefix assumed; both
 * EHR addressing forms; write responses carry the acting-endpoint headers with
 * Location/ETag untouched) and the converse half of Track 10: a COMPOSITION
 * committed with a DV_IDENTIFIER in its content must arrive at the node
 * byte-identical. Track 10 "cuts both ways" — a gateway that rewrites a write
 * body fails it — and that direction is what this class pins.
 */
@Tag("TRACK-9")
@Tag("TRACK-10")
class ProxyByteIdentityIT extends IntegrationTestBase {

    @Autowired
    private SystemIdMappingRepository systemIdMappings;

    @Test
    @Tag("CP-21")
    @Tag("CP-22")
    @Tag("CP-24")
    void responseBytesAndEtagPassThroughUnmodified() {
        // deliberately odd whitespace + unicode: any converter interference would show
        String body = "{\n\t\"_type\" : \"EHR\",  \"ehr_id\": {\"value\":\"" + EHR_NODE1 + "\"}, \"note\":\"żółć\"}";
        NODE_1.stubFor(get(urlPathEqualTo("/v1/ehr/" + EHR_NODE1))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"55e26788-9b06-4ecc-9748-dbcb84f5e089::cdr1.test::2\"")
                        .withBody(body.getBytes(StandardCharsets.UTF_8))));

        ResponseEntity<byte[]> response = exchangeBytes(HttpMethod.GET,
                "/v1/ehr/" + EHR_NODE1, null,
                Map.of("openEHR-federation-endpoint", "node_1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeaders().getFirst("ETag"))
                .isEqualTo("\"55e26788-9b06-4ecc-9748-dbcb84f5e089::cdr1.test::2\"");
        assertThat(response.getHeaders().getFirst("openEHR-federation-endpoint")).isEqualTo("node_1");
        assertThat(response.getHeaders().getFirst("openEHR-federation-system-id")).isEqualTo("cdr1.test");
    }

    @Test
    @Tag("CP-24")
    @Tag("CP-26")
    void writeBodyReachesTheNodeByteIdentical() {
        byte[] composition = ("{  \"_type\":\"COMPOSITION\",\n\"name\": {\"value\":\"enc\"} , \"x\":1}\n")
                .getBytes(StandardCharsets.UTF_8);
        NODE_1.stubFor(post(urlPathEqualTo("/v1/ehr/" + EHR_NODE1 + "/composition"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("ETag", "\"8849182a-1d4b-4e3d-a3f3-f303d2f4f34b::cdr1.test::1\"")
                        .withHeader("Location", NODE_1.baseUrl() + "/v1/ehr/" + EHR_NODE1
                                + "/composition/8849182a-1d4b-4e3d-a3f3-f303d2f4f34b::cdr1.test::1")));

        ResponseEntity<byte[]> response = exchangeBytes(HttpMethod.POST,
                "/v1/ehr/" + EHR_NODE1 + "/composition", composition,
                Map.of("openEHR-federation-endpoint", "node_1",
                        "Content-Type", "application/json"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        NODE_1.verify(postRequestedFor(urlPathEqualTo("/v1/ehr/" + EHR_NODE1 + "/composition"))
                .withRequestBody(binaryEqualTo(composition)));
        // Location passed through byte-identical — never rewritten to the gateway host
        assertThat(response.getHeaders().getFirst("Location")).startsWith(NODE_1.baseUrl());
        assertThat(response.getHeaders().getFirst("openEHR-federation-endpoint")).isEqualTo("node_1");
    }

    @Test
    @Tag("CP-13")
    void observedCreatingSystemIdsAreLearnedIntoTheRegistry() {
        NODE_1.stubFor(post(urlPathEqualTo("/v1/ehr/" + EHR_NODE1 + "/composition"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("ETag", "\"9949182a-1d4b-4e3d-a3f3-f303d2f4f34b::imported.system.example::1\"")));

        exchangeBytes(HttpMethod.POST, "/v1/ehr/" + EHR_NODE1 + "/composition",
                "{}".getBytes(StandardCharsets.UTF_8),
                Map.of("openEHR-federation-endpoint", "node_1", "Content-Type", "application/json"));

        assertThat(systemIdMappings.findById("imported.system.example"))
                .hasValueSatisfying(m -> {
                    assertThat(m.nodeId()).isEqualTo("node_1");
                    assertThat(m.source()).isEqualTo("observed");
                });
    }

    @Test
    @Tag("CP-25")
    void demographicAnswers501() {
        ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.GET,
                "/v1/demographic/party/123", null, Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(501);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_NOT_IMPLEMENTED");
    }

    @Test
    @Tag("CP-34")
    void definitionRequiresAnExplicitSingleTarget() {
        ResponseEntity<JsonNode> noTarget = exchangeJson(HttpMethod.GET,
                "/v1/definition/template/adl1.4", null, Map.of());
        assertThat(noTarget.getStatusCode().value()).isEqualTo(400);
        assertThat(noTarget.getBody().get("error").asText()).isEqualTo("FED_NO_TARGET");

        NODE_2.stubFor(get(urlPathEqualTo("/v1/definition/template/adl1.4"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"template_id\":\"encounter.v1\"}]")));
        ResponseEntity<JsonNode> routed = exchangeJson(HttpMethod.GET,
                "/v1/definition/template/adl1.4", null,
                Map.of("openEHR-federation-endpoint", "node_2"));
        assertThat(routed.getStatusCode().value()).isEqualTo(200);
        assertThat(routed.getHeaders().getFirst("openEHR-federation-endpoint")).isEqualTo("node_2");
    }

    @Test
    void storedQueriesAnswer501() {
        ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.GET,
                "/v1/query/org.example::encounters", null, Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(501);
    }
}
