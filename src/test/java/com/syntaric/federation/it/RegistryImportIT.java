// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.federation.config.RegistryBootstrapLoader;
import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.registry.Endpoint;
import com.syntaric.federation.registry.EndpointRepository;
import com.syntaric.federation.registry.NodeIdentifierRepository;
import com.syntaric.federation.registry.NodeRepository;
import com.syntaric.federation.registry.OrganisationRepository;
import com.syntaric.federation.registry.RegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry bootstrap document — how a gateway that exposes no write API is
 * handed a federation.
 *
 * <p>The property that matters is that applying a document is a <b>command the
 * operator issues</b>, not a policy the gateway enforces: it is idempotent per
 * id, and it never deletes. A document that happens to omit a source must leave
 * that source alone, or a partial document would silently drop live sources out
 * of the federation.
 *
 * <p>These assertions are transport-agnostic on purpose. They previously ran
 * against an admin REST endpoint and now drive {@link RegistryBootstrapLoader}
 * against a file; what is being pinned is the import semantics, which are the
 * same either way.
 */
@Tag("CP-17")
class RegistryImportIT extends IntegrationTestBase {

    @Autowired
    private OrganisationRepository organisations;
    @Autowired
    private NodeRepository nodes;
    @Autowired
    private EndpointRepository endpoints;
    @Autowired
    private NodeIdentifierRepository nodeIdentifiers;
    @Autowired
    private RegistryService registry;
    /** Constructed, not injected: the container's mapper is a different Jackson major version. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanUp() {
        // Identifiers first: node_identifier FKs node, so a leftover row blocks
        // the node delete below and leaves imp-node in the shared registry.
        nodeIdentifiers.findAll().stream()
                .filter(i -> i.nodeId().startsWith("imp-")).forEach(nodeIdentifiers::delete);
        endpoints.findAll().stream().filter(e -> e.endpointId().startsWith("imp-")).forEach(endpoints::delete);
        nodes.findAll().stream().filter(n -> n.nodeId().startsWith("imp-")).forEach(nodes::delete);
        organisations.findAll().stream().filter(o -> o.id().startsWith("imp-")).forEach(organisations::delete);
    }

    private static Map<String, Object> document(String orgName, String baseUrl) {
        return Map.of(
                "organisations", List.of(Map.of("id", "imp-org", "name", orgName)),
                "nodes", List.of(Map.of("nodeId", "imp-node", "systemId", "imp.test",
                        "organisationId", "imp-org", "product", "EHRbase", "version", "2.19.0")),
                "endpoints", List.of(Map.of("endpointId", "imp-ep", "nodeId", "imp-node",
                        "baseUrl", baseUrl, "status", "active")));
    }

    @Test
    @DisplayName("a bootstrap document creates the rows it describes")
    void bootstrapCreatesRows() throws Exception {
        load(document("Imported Org", "http://localhost:9/openehr"));

        assertThat(organisations.findById("imp-org")).get()
                .satisfies(o -> assertThat(o.name()).isEqualTo("Imported Org"));
        assertThat(nodes.findById("imp-node")).get()
                .satisfies(n -> assertThat(n.systemId()).isEqualTo("imp.test"));
        assertThat(endpoints.findById("imp-ep")).get()
                .satisfies(e -> assertThat(e.connectionType())
                        .as("connection_type stays server-owned, even on import")
                        .isEqualTo("openehr-rest"));
    }

    @Test
    @DisplayName("re-applying the same document creates nothing — idempotent per id")
    void reapplyingIsIdempotent() throws Exception {
        load(document("Imported Org", "http://localhost:9/openehr"));
        load(document("Renamed Org", "http://localhost:10/openehr"));

        assertThat(organisations.findById("imp-org")).get()
                .satisfies(o -> assertThat(o.name()).isEqualTo("Renamed Org"));
        assertThat(endpoints.findById("imp-ep")).get()
                .satisfies(e -> assertThat(e.baseUrl()).isEqualTo("http://localhost:10/openehr"));
        assertThat(organisations.findAll().stream().filter(o -> o.id().startsWith("imp-")))
                .as("an id is upserted, never duplicated")
                .hasSize(1);
    }

    @Test
    @DisplayName("rows absent from the document are left alone — an import never deletes")
    void absentRowsAreUntouched() throws Exception {
        load(document("Imported Org", "http://localhost:9/openehr"));

        // A document describing only the fixture's org: it says nothing about imp-*.
        load(Map.of(
                "organisations", List.of(Map.of("id", "org-a", "name", "Org A")),
                "nodes", List.of(),
                "endpoints", List.of()));

        assertThat(endpoints.existsById("imp-ep"))
                .as("omission is not deletion — otherwise a partial document drops live sources")
                .isTrue();
        assertThat(nodes.existsById("imp-node")).isTrue();
        assertThat(organisations.existsById("imp-org")).isTrue();
    }

    @Test
    @DisplayName("an import preserves measured latency it knows nothing about")
    void importPreservesMeasuredLatency() throws Exception {
        load(document("Imported Org", "http://localhost:9/openehr"));
        endpoints.findById("imp-ep").ifPresent(e -> endpoints.save(
                Endpoint.withoutCredentials(e.endpointId(), e.nodeId(), e.baseUrl(),
                        e.pixManagerUrl(), e.connectionType(), e.authProfile(), e.status(), 240L)));

        load(document("Imported Org", "http://localhost:9/openehr"));

        assertThat(endpoints.findById("imp-ep")).get()
                .satisfies(e -> assertThat(e.latencyP50Ms())
                        .as("latency belongs to this deployment, not to the document")
                        .isEqualTo(240L));
    }

    @Test
    @DisplayName("a partial document is accepted: only the sections present are applied")
    void partialDocumentIsAccepted() throws Exception {
        load(Map.of("organisations", List.of(Map.of("id", "imp-org", "name", "Only An Org"))));

        assertThat(organisations.existsById("imp-org")).isTrue();
    }

    /**
     * Every component of {@link Endpoint} round-trips to the column that belongs
     * to it.
     *
     * <p>Spring Data JDBC binds by name, but every construction site is
     * positional, so a component added or removed in the wrong place misbinds
     * silently rather than failing to compile. Each value below is distinctive,
     * so a shift by one lands a recognisably wrong value in the next column and
     * this fails loudly instead of passing with a scrambled row.
     */
    @Test
    @DisplayName("every endpoint component round-trips to its own column")
    void everyEndpointComponentRoundTrips() throws Exception {
        Map<String, Object> endpoint = new HashMap<>();
        endpoint.put("endpointId", "imp-ep");
        endpoint.put("nodeId", "imp-node");
        endpoint.put("baseUrl", "http://base.example/openehr");
        endpoint.put("pixManagerUrl", "http://pix.example/pix");
        endpoint.put("authProfile", "oauth2-secret");
        endpoint.put("status", "offline");
        endpoint.put("smartClientId", "smart-client");
        endpoint.put("smartTokenEndpoint", "http://smart.example/token");
        endpoint.put("smartScope", "system/Patient.read");
        endpoint.put("oauthClientId", "oauth-client");
        endpoint.put("oauthTokenEndpoint", "http://oauth.example/token");
        endpoint.put("oauthScope", "federation/read");
        endpoint.put("oauthAuthMethod", Endpoint.AUTH_METHOD_POST);
        endpoint.put("oauthAudience", "urn:audience:node");

        load(Map.of(
                "organisations", List.of(Map.of("id", "imp-org", "name", "Imported Org")),
                "nodes", List.of(Map.of("nodeId", "imp-node", "systemId", "imp.test",
                        "organisationId", "imp-org")),
                "endpoints", List.of(endpoint)));

        assertThat(endpoints.findById("imp-ep")).get().satisfies(e -> {
            assertThat(e.baseUrl()).isEqualTo("http://base.example/openehr");
            assertThat(e.pixManagerUrl()).isEqualTo("http://pix.example/pix");
            assertThat(e.authProfile()).isEqualTo("oauth2-secret");
            assertThat(e.status()).isEqualTo("offline");
            assertThat(e.latencyP50Ms()).as("not in the document, so still unmeasured").isNull();
            assertThat(e.smartClientId()).isEqualTo("smart-client");
            assertThat(e.smartTokenEndpoint()).isEqualTo("http://smart.example/token");
            assertThat(e.smartScope()).isEqualTo("system/Patient.read");
            assertThat(e.oauthClientId()).isEqualTo("oauth-client");
            assertThat(e.oauthTokenEndpoint()).isEqualTo("http://oauth.example/token");
            assertThat(e.oauthScope()).isEqualTo("federation/read");
            assertThat(e.oauthAuthMethod()).isEqualTo(Endpoint.AUTH_METHOD_POST);
            assertThat(e.oauthAudience()).isEqualTo("urn:audience:node");
        });
    }

    @Test
    @DisplayName("an endpoint referencing a node the document does not supply fails the whole import")
    void danglingReferenceIsRejected() {
        assertThatThrownBy(() -> load(Map.of(
                "organisations", List.of(),
                "nodes", List.of(),
                "endpoints", List.of(Map.of("endpointId", "imp-ep", "nodeId", "no-such-node",
                        "baseUrl", "http://localhost:9/openehr")))))
                .isInstanceOf(Exception.class);

        assertThat(endpoints.existsById("imp-ep"))
                .as("the whole import is one transaction — nothing half-applies")
                .isFalse();
    }

    /**
     * Node identifiers travel in the document too (N4 / CP-5).
     *
     * <p>Without this a federation moved to another deployment arrives with
     * localization configured and no way to map an answer back to a node — every
     * query would localize to nothing, which looks like a localization bug
     * rather than a missing section of the document.
     */
    @Test
    @DisplayName("node identifiers are applied, and replaying does not duplicate them")
    void identifiersAreAppliedWithoutDuplicating() throws Exception {
        Map<String, Object> document = new HashMap<>(document("Imported", "http://localhost:1/openehr"));
        document.put("identifiers", List.of(
                Map.of("nodeId", "imp-node", "system", "ura", "value", "00077777"),
                Map.of("nodeId", "imp-node", "system", "home-community-id",
                        "value", "urn:oid:1.2.3.4.5")));

        load(document);

        assertThat(identifiersOf("imp-node")).containsExactlyInAnyOrder(
                "ura|00077777", "home-community-id|urn:oid:1.2.3.4.5");

        // Replaying must not duplicate the triple — matching is on the natural
        // key, not on the surrogate id another deployment's export carries.
        load(document);

        assertThat(identifiersOf("imp-node")).hasSize(2);
    }

    /** An identifier naming an unknown node fails the import rather than vanishing. */
    @Test
    void anIdentifierForAnUnknownNodeIsRejected() {
        Map<String, Object> document = new HashMap<>(document("Imported", "http://localhost:1/openehr"));
        document.put("identifiers", List.of(
                Map.of("nodeId", "imp-missing", "system", "ura", "value", "00088888")));

        assertThatThrownBy(() -> load(document)).isInstanceOf(Exception.class);
    }

    // ---- the loader's own contract ------------------------------------------

    @Test
    @DisplayName("no bootstrap file configured is a silent no-op")
    void anUnsetBootstrapFileDoesNothing() throws Exception {
        loaderFor(null).run(null);

        assertThat(organisations.existsById("imp-org")).isFalse();
    }

    /**
     * A configured-but-missing file fails the boot.
     *
     * <p>Starting anyway would leave a gateway with an empty federation that is
     * not visibly broken: it answers queries, returns 200, and reports zero rows
     * — indistinguishable from a patient genuinely having no records.
     */
    @Test
    @DisplayName("a configured but missing bootstrap file fails startup")
    void aMissingBootstrapFileFailsStartup() {
        assertThatThrownBy(() -> loaderFor(
                new FileSystemResource(tempDir.resolve("absent.json"))).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no such resource");
    }

    @Test
    @DisplayName("a malformed bootstrap document fails startup rather than importing nothing")
    void aMalformedBootstrapFileFailsStartup() throws Exception {
        Path file = tempDir.resolve("broken.json");
        Files.writeString(file, "{ this is not json");

        assertThatThrownBy(() -> loaderFor(new FileSystemResource(file)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not read");
    }

    // ---- helpers -------------------------------------------------------------

    /** Writes the document to a temp file and applies it exactly as startup would. */
    private void load(Map<String, Object> document) throws Exception {
        Path file = tempDir.resolve("registry-" + System.nanoTime() + ".json");
        Files.writeString(file, objectMapper.writeValueAsString(document));
        loaderFor(new FileSystemResource(file)).run(null);
    }

    private RegistryBootstrapLoader loaderFor(org.springframework.core.io.Resource resource) {
        return new RegistryBootstrapLoader(registry,
                new FederationProperties(null, null, null, null, null,
                        new FederationProperties.Registry(resource)));
    }

    private List<String> identifiersOf(String nodeId) {
        List<String> found = new ArrayList<>();
        nodeIdentifiers.findAll().forEach(identifier -> {
            if (nodeId.equals(identifier.nodeId())) {
                found.add(identifier.system() + "|" + identifier.value());
            }
        });
        return found;
    }
}
