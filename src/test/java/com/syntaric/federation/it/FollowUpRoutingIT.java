// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.syntaric.federation.registry.EhrNodeIndexEntry;
import com.syntaric.federation.registry.EhrNodeIndexRepository;
import com.syntaric.federation.registry.IntegrityIncident;
import com.syntaric.federation.registry.IntegrityIncidentRepository;
import com.syntaric.federation.registry.ResolutionBindingRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan M6: uid-parsed follow-up routing, controlling-system write rules
 * (N23/N36), ehr_id routing priority and collision handling (N41/N42).
 *
 * <p>Executes spec §16.3 Track 6 (a follow-up read/write is located by the
 * path {@code ehr_id}, with {@code creating_system_id} as step 4 and as the
 * write control test; the imported-copy read and write cases; uids unchanged;
 * an unroutable write rejected) and the gateway half of Track 11 (a seeded duplicate {@code ehr_id}
 * across two nodes yields 409, never a served row or an applied write). Track
 * 11's node-admission half (§12b.2) is an operator obligation and is deferred
 * in {@link com.syntaric.federation.conformance.ConformanceMatrixTest}. The
 * specification does not say who is responsible for seeding the duplicate that
 * the track calls for, which is why only the gateway half is asserted here.
 */
@Tag("TRACK-6")
class FollowUpRoutingIT extends IntegrationTestBase {

    @Autowired
    private EhrNodeIndexRepository ehrIndex;
    @Autowired
    private IntegrityIncidentRepository incidents;
    @Autowired
    private JdbcAggregateTemplate template;
    @Autowired
    private ResolutionBindingRepository bindings;

    private static String versionUid(String creatingSystem) {
        return "8849182a-1d4b-4e3d-a3f3-f303d2f4f34b::" + creatingSystem + "::1";
    }

    /**
     * The IT database is shared and the gateway learns as it serves, so pin
     * where a test's ehr_id is located: drop whatever earlier classes learned
     * for it and, when {@code nodeId} is given, index it there (N41 step 3).
     */
    private void locateAt(String ehrId, String nodeId) {
        bindings.deleteAll(bindings.findByLocalEhrId(ehrId));
        ehrIndex.deleteAll(ehrIndex.findByEhrId(ehrId));
        if (nodeId != null) {
            template.insert(new EhrNodeIndexEntry(null, ehrId, nodeId, Instant.now()));
        }
    }

    @Test
    @Tag("CP-15")
    @Tag("CP-29")
    void versionedWriteRoutesToTheControllingSystem() {
        locateAt(EHR_NODE2, null);
        String path = "/v1/ehr/" + EHR_NODE2 + "/composition/" + versionUid("cdr2.test");
        NODE_2.stubFor(put(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"" + versionUid("cdr2.test").replace("::1", "::2") + "\"")));

        ResponseEntity<byte[]> response = exchangeBytes(HttpMethod.PUT, path,
                "{}".getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/json"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        NODE_2.verify(putRequestedFor(urlPathEqualTo(path)));
        assertThat(response.getHeaders().getFirst("openEHR-federation-endpoint")).isEqualTo("node_2");
    }

    @Test
    @Tag("CP-15")
    @Tag("CP-29")
    void writeDirectedAtACopyHolderIs409NamingTheControllingSystem() {
        String path = "/v1/ehr/" + EHR_NODE1 + "/composition/" + versionUid("cdr2.test");

        ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.PUT, path,
                Map.of(), Map.of("openEHR-federation-endpoint", "node_1"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_WRONG_CONTROLLING_SYSTEM");
        assertThat(response.getBody().get("details").get("controlling_system_id").asText())
                .isEqualTo("cdr2.test");
        assertThat(NODE_1.getAllServeEvents()).isEmpty();
    }

    @Test
    @Tag("CP-15")
    void writeWhoseControllingSystemIsUnreachableIs409NotAFork() {
        locateAt(EHR_NODE1, "node_1");
        String path = "/v1/ehr/" + EHR_NODE1 + "/composition/" + versionUid("unknown.system");

        ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.PUT, path, Map.of(), Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().get("details").get("controlling_system_id").asText())
                .isEqualTo("unknown.system");
        assertThat(NODE_1.getAllServeEvents()).isEmpty();
    }

    @Test
    @Tag("CP-14")
    @Tag("CP-33")
    void readWithNoBindingOrIndexReachesTheOwnerThroughCreatingSystemId() {
        locateAt(EHR_NODE3, null);
        String path = "/v1/ehr/" + EHR_NODE3 + "/composition/" + versionUid("cdr3.test");
        NODE_3.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"_type\":\"COMPOSITION\"}")));

        ResponseEntity<byte[]> response = exchangeBytes(HttpMethod.GET, path, null, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        NODE_3.verify(getRequestedFor(urlPathEqualTo(path)));
        assertThat(response.getHeaders().getFirst("openEHR-federation-system-id")).isEqualTo("cdr3.test");
        // step 4, not a probe: no other node was asked
        assertThat(NODE_1.getAllServeEvents()).isEmpty();
        assertThat(NODE_2.getAllServeEvents()).isEmpty();
    }

    @Test
    @Tag("CP-14")
    @Tag("CP-33")
    void importedCopyReadReturnsTheHoldersCopyAndSaysSo() {
        // node_1 holds a copy of a version created at cdr2.test, under node_1's own ehr_id
        locateAt(EHR_NODE1, "node_1");
        String path = "/v1/ehr/" + EHR_NODE1 + "/composition/" + versionUid("cdr2.test");
        NODE_1.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"" + versionUid("cdr2.test") + "\"")
                .withBody("{\"_type\":\"COMPOSITION\"}")));

        ResponseEntity<byte[]> response = exchangeBytes(HttpMethod.GET, path, null, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        NODE_1.verify(getRequestedFor(urlPathEqualTo(path)));
        // the path ehr_id is node_1's: forwarding it to the owner would be wrong (§12.5.1)
        assertThat(NODE_2.getAllServeEvents()).isEmpty();
        // copy detection: the holder's system_id differs from the uid's creating_system_id
        assertThat(response.getHeaders().getFirst("openEHR-federation-system-id")).isEqualTo("cdr1.test");
        assertThat(response.getHeaders().getFirst("ETag")).contains("::cdr2.test::");
    }

    @Test
    @Tag("CP-29")
    @Tag("CP-33")
    void importedCopyWriteWithTheHoldersEhrIdIs409AndTheOwnerIsNeverCalled() {
        locateAt(EHR_NODE1, "node_1");
        String path = "/v1/ehr/" + EHR_NODE1 + "/composition/" + versionUid("cdr2.test");

        ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.PUT, path, Map.of(), Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_WRONG_CONTROLLING_SYSTEM");
        assertThat(response.getBody().get("details").get("controlling_system_id").asText())
                .isEqualTo("cdr2.test");
        // rejected before forwarding, and never redirected to the owner under node_1's ehr_id
        assertThat(NODE_1.getAllServeEvents()).isEmpty();
        assertThat(NODE_2.getAllServeEvents()).isEmpty();
    }

    @Test
    @Tag("CP-33")
    void explicitTargetContradictingTheIndexStillWins() {
        locateAt(EHR_NODE1, "node_1");
        String path = "/v1/ehr/" + EHR_NODE1 + "/ehr_status";
        // node_3 does not hold this ehr_id. A 200 there would be a second claim on
        // it, which the gateway learns from the response and reports as a
        // collision (N42), so the node's own 404 is the clean way to show step 1
        // was followed: the request went where the header said, not where the
        // index pointed.
        NODE_3.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(404)));

        ResponseEntity<byte[]> response = exchangeBytes(HttpMethod.GET, path, null,
                Map.of("openEHR-federation-endpoint", "node_3"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getFirst("openEHR-federation-endpoint")).isEqualTo("node_3");
        NODE_3.verify(getRequestedFor(urlPathEqualTo(path)));
        assertThat(NODE_1.getAllServeEvents()).isEmpty();
    }

    @Test
    @Tag("CP-33")
    void writeWithUnresolvableEhrIdIs400NeverAskAll() {
        String unknownEhr = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.POST,
                "/v1/ehr/" + unknownEhr + "/composition", Map.of(), Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_NO_TARGET");
        // no node was probed
        assertThat(NODE_1.getAllServeEvents()).isEmpty();
        assertThat(NODE_2.getAllServeEvents()).isEmpty();
        assertThat(NODE_3.getAllServeEvents()).isEmpty();
    }

    @Test
    @Tag("CP-33")
    void askAllReadProbeFindsTheOwnerAndLearnsTheIndex() {
        String ehrId = UUID.randomUUID().toString();
        NODE_1.stubFor(get(urlPathEqualTo("/v1/ehr/" + ehrId))
                .willReturn(aResponse().withStatus(404)));
        NODE_2.stubFor(get(urlPathEqualTo("/v1/ehr/" + ehrId))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{\"_type\":\"EHR\"}")));
        NODE_3.stubFor(get(urlPathEqualTo("/v1/ehr/" + ehrId))
                .willReturn(aResponse().withStatus(404)));

        ResponseEntity<byte[]> response = exchangeBytes(HttpMethod.GET, "/v1/ehr/" + ehrId, null, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("openEHR-federation-endpoint")).isEqualTo("node_2");
        assertThat(ehrIndex.findByEhrId(ehrId))
                .extracting(EhrNodeIndexEntry::nodeId).containsExactly("node_2");
    }

    @Test
    @Tag("CP-33")
    void ehrIdClaimedByTwoNodesIs409PlusIntegrityIncident() {
        String ehrId = UUID.randomUUID().toString();
        template.insert(new EhrNodeIndexEntry(null, ehrId, "node_1", Instant.now()));
        template.insert(new EhrNodeIndexEntry(null, ehrId, "node_2", Instant.now()));
        long incidentsBefore = incidents.findByType(IntegrityIncident.TYPE_EHR_ID_COLLISION).size();

        ResponseEntity<JsonNode> response = exchangeJson(HttpMethod.GET,
                "/v1/ehr/" + ehrId, null, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().get("error").asText()).isEqualTo("FED_EHR_ID_COLLISION");
        assertThat(response.getBody().get("details").get("nodes").asText())
                .contains("node_1").contains("node_2");
        assertThat(incidents.findByType(IntegrityIncident.TYPE_EHR_ID_COLLISION))
                .hasSize((int) incidentsBefore + 1);
    }

    @Test
    @Tag("CP-33")
    void ehrRoutedReadUsesTheLearnedIndexWithoutProbing() {
        String path = "/v1/ehr/" + EHR_NODE1 + "/ehr_status";
        // The Postgres container is shared across the whole IT suite and the gateway
        // learns as it serves, so an earlier class may already have bound EHR_NODE1
        // to a node — and a resolution binding outranks the ehr_id index in
        // EhrIdRouter, so the routed read below would go somewhere unstubbed and
        // 404. Clearing both learned tables for this one ehr_id makes the test
        // independent of where it lands in the execution order.
        locateAt(EHR_NODE1, null);

        // learn the index first via an ask-all read
        NODE_1.stubFor(get(urlPathEqualTo("/v1/ehr/" + EHR_NODE1))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));
        NODE_2.stubFor(get(urlPathEqualTo("/v1/ehr/" + EHR_NODE1))
                .willReturn(aResponse().withStatus(404)));
        NODE_3.stubFor(get(urlPathEqualTo("/v1/ehr/" + EHR_NODE1))
                .willReturn(aResponse().withStatus(404)));
        // The learning read is a precondition, not the subject — assert it landed
        // so a failure below is about routing rather than about setup.
        ResponseEntity<byte[]> learning =
                exchangeBytes(HttpMethod.GET, "/v1/ehr/" + EHR_NODE1, null, Map.of());
        assertThat(learning.getStatusCode().value()).isEqualTo(200);
        assertThat(ehrIndex.findByEhrId(EHR_NODE1))
                .as("the ask-all read must have learned the index")
                .isNotEmpty();

        // the learning probe legitimately fans out to every node; forget it
        NODE_1.resetRequests();
        NODE_2.resetRequests();
        NODE_3.resetRequests();
        NODE_1.stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        ResponseEntity<byte[]> response = exchangeBytes(HttpMethod.GET, path, null, Map.of());

        // The body is in the message because the informative failure here is a
        // 502 naming an endpoint this test never heard of — a source another IT
        // class created and left active in the shared registry.
        assertThat(response.getStatusCode().value())
                .as("routed read of %s returned %s", path,
                        new String(response.getBody() == null ? new byte[0] : response.getBody()))
                .isEqualTo(200);
        // exactly one request: the routed read itself, no probe fan-out
        assertThat(NODE_1.getAllServeEvents()).hasSize(1);
        assertThat(NODE_2.getAllServeEvents()).isEmpty();
    }
}
