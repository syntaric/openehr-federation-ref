// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.federation.query.OutboundCredentials;
import com.syntaric.federation.outbound.auth.OAuth2ClientCredentialsAuthProvider;
import com.syntaric.federation.outbound.auth.SmartBackendServicesAuthProvider;
import com.syntaric.federation.registry.Endpoint;
import com.syntaric.federation.registry.EndpointRepository;
import com.syntaric.federation.registry.NodeRepository;
import com.syntaric.federation.registry.OrganisationRepository;
import com.syntaric.federation.registry.RegistryService;
import com.syntaric.federation.registry.RegistryService.OutboundAuthSpec;
import com.syntaric.federation.security.OutboundCredentialsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The secret-handling contract for per-endpoint outbound credentials.
 *
 * <p>Two properties, both of which fail silently if broken — which is why they
 * are pinned here rather than left to review:
 *
 * <ol>
 *   <li><b>No secret leaves the server.</b> Not the plaintext, and not the
 *       ciphertext either: a registry document carrying ciphertext would spread
 *       the credentials of a whole federation into whatever that document gets
 *       copied into.</li>
 *   <li><b>An absent secret means "keep".</b> Export and import are the same
 *       document, and secrets are omitted from the export — so an import that
 *       read "absent" as "clear" would null every credential in the registry the
 *       first time someone re-applied their own export. The federation would keep
 *       working until each cached token expired, which is the worst possible way
 *       to find out.</li>
 * </ol>
 */
@Tag("CP-16")
@Tag("CP-17")
class OutboundAuthSecretIT extends IntegrationTestBase {

    private static final String SECRET = "s3cr3t-client-secret";
    /** Both profiles are OAuth2 client_credentials; the name says how the client authenticates. */
    private static final String OAUTH2_JWT = SmartBackendServicesAuthProvider.PROFILE;
    private static final String OAUTH2_SECRET = OAuth2ClientCredentialsAuthProvider.PROFILE;

    @Autowired
    private OrganisationRepository organisations;
    @Autowired
    private NodeRepository nodes;
    @Autowired
    private EndpointRepository endpoints;
    @Autowired
    private RegistryService registry;
    @Autowired
    private OutboundCredentialsResolver credentials;
    /** Constructed, not injected: the container's mapper is a different Jackson major version. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedOrgAndNode() {
        if (!organisations.existsById("sec-org")) {
            registry.createOrganisation("sec-org", "Secret Org");
        }
        if (!nodes.existsById("sec-node")) {
            registry.createNode("sec-node", "sec.test", "sec-org", null, null);
        }
    }

    @AfterEach
    void cleanUp() {
        endpoints.findAll().stream().filter(e -> e.endpointId().startsWith("sec-"))
                .forEach(endpoints::delete);
        nodes.findAll().stream().filter(n -> n.nodeId().startsWith("sec-")).forEach(nodes::delete);
        organisations.findAll().stream().filter(o -> o.id().startsWith("sec-"))
                .forEach(organisations::delete);
    }

    @Test
    @DisplayName("a stored secret is encrypted at rest and readable back as plaintext")
    void secretsAreEncryptedAtRestAndDecryptable() {
        createEndpointWithSecret(SECRET);

        Endpoint stored = endpoints.findById("sec-ep").orElseThrow();
        assertThat(stored.oauthClientSecretEnc())
                .as("the column holds ciphertext, never the secret itself")
                .isNotNull()
                .startsWith("v1:")
                .doesNotContain(SECRET);

        assertThat(credentials.resolve(stored).oauthClientSecret())
                .as("and it decrypts back to exactly what was stored")
                .isEqualTo(SECRET);
    }

    @Test
    @DisplayName("a serialised registry document contains neither the secret nor its ciphertext")
    void exportOmitsSecretsEntirely() throws Exception {
        createEndpointWithSecret(SECRET);

        String document = objectMapper.writeValueAsString(endpoints.findById("sec-ep").orElseThrow());

        assertThat(document)
                .as("ciphertext is as dangerous to copy around as plaintext")
                .doesNotContain(SECRET)
                .doesNotContain("oauthClientSecretEnc")
                .doesNotContain("smartSigningKeyEnc");
        // The non-secret fields DO travel: they are operator configuration.
        assertThat(document).contains("sec-client").contains("https://as.test/token");
    }

    @Test
    @DisplayName("re-applying a document that omits the secret leaves it intact and usable")
    void theExportImportRoundTripPreservesSecrets() {
        createEndpointWithSecret(SECRET);

        // Exactly what an export produces: every field except the ciphertext.
        Endpoint exported = objectMapper.convertValue(
                objectMapper.valueToTree(endpoints.findById("sec-ep").orElseThrow()), Endpoint.class);
        assertThat(exported.oauthClientSecretEnc())
                .as("precondition: the exported shape really does omit the ciphertext")
                .isNull();

        registry.importRegistry(List.of(), List.of(), List.of(exported), List.of());

        assertThat(credentials.resolve(endpoints.findById("sec-ep").orElseThrow()).oauthClientSecret())
                .as("an absent secret means keep — otherwise re-applying your own "
                        + "export silently nulls every credential in the federation")
                .isEqualTo(SECRET);
    }

    @Test
    @DisplayName("an ordinary edit that omits the secret keeps it")
    void anUpdateWithoutTheSecretPreservesIt() {
        createEndpointWithSecret(SECRET);

        // A base-URL change carrying no secret: null means "unchanged".
        registry.updateEndpoint("sec-ep", "sec-node", "http://localhost:10/openehr", null,
                OAUTH2_SECRET, "active",
                new OutboundAuthSpec(null, null, null, null,
                        "sec-client", "https://as.test/token", null, null, null, null));

        Endpoint after = endpoints.findById("sec-ep").orElseThrow();
        assertThat(after.baseUrl()).isEqualTo("http://localhost:10/openehr");
        assertThat(credentials.resolve(after).oauthClientSecret())
                .as("a base URL typo must not discard the credential")
                .isEqualTo(SECRET);
    }

    @Test
    @DisplayName("an explicit empty string clears the secret")
    void anExplicitEmptyStringClearsTheSecret() {
        createEndpointWithSecret(SECRET);

        registry.updateEndpoint("sec-ep", "sec-node", "http://localhost:9/openehr", null,
                OAUTH2_SECRET, "active",
                new OutboundAuthSpec(null, null, null, null,
                        "sec-client", "https://as.test/token", "", null, null, null));

        Endpoint after = endpoints.findById("sec-ep").orElseThrow();
        assertThat(after.oauthClientSecretEnc())
                .as("blank is the only way to clear — distinct from null, which keeps")
                .isNull();
        assertThat(after.oauthClientSecretSet()).isFalse();
    }

    @Test
    @DisplayName("an endpoint with no credentials reads back as absent, not as an error")
    void endpointsWithoutCredentialsHaveNullCredentials() {
        registry.createEndpoint("sec-ep", "sec-node", "http://localhost:9/openehr", null,
                "passthrough", "active");

        Endpoint stored = endpoints.findById("sec-ep").orElseThrow();
        assertThat(stored.smartSigningKeyEnc()).isNull();
        assertThat(stored.oauthClientSecretEnc()).isNull();
        assertThat(stored.hasEncryptedSecret()).isFalse();
        assertThat(stored.smartSigningKeySet()).isFalse();
        assertThat(stored.oauthClientSecretSet()).isFalse();
    }

    @Test
    @DisplayName("every credential field round-trips through create and read")
    void allCredentialFieldsRoundTrip() throws Exception {
        String pem = Files.readString(Path.of("src/test/resources/keys/test-private.pem"));

        registry.createEndpoint("sec-ep", "sec-node", "http://localhost:9/openehr", null,
                OAUTH2_JWT, "active",
                new OutboundAuthSpec("smart-client", "https://smart.test/token", pem,
                        "system/Patient.read",
                        "oauth-client", "https://oauth.test/token", SECRET, "federation/read",
                        Endpoint.AUTH_METHOD_POST, "urn:audience:node"));

        OutboundCredentials resolved = credentials.resolve(endpoints.findById("sec-ep").orElseThrow());
        assertThat(resolved.smartClientId()).isEqualTo("smart-client");
        assertThat(resolved.smartTokenEndpoint()).isEqualTo("https://smart.test/token");
        assertThat(resolved.smartSigningKeyPem()).isEqualTo(pem);
        assertThat(resolved.smartScope()).isEqualTo("system/Patient.read");
        assertThat(resolved.oauthClientId()).isEqualTo("oauth-client");
        assertThat(resolved.oauthTokenEndpoint()).isEqualTo("https://oauth.test/token");
        assertThat(resolved.oauthClientSecret()).isEqualTo(SECRET);
        assertThat(resolved.oauthScope()).isEqualTo("federation/read");
        assertThat(resolved.oauthAuthMethod()).isEqualTo(Endpoint.AUTH_METHOD_POST);
        assertThat(resolved.oauthAudience()).isEqualTo("urn:audience:node");
    }

    /**
     * The two token-acquiring profiles were once called {@code smart} and
     * {@code oauth2}; they are now {@code oauth2-jwt} and {@code oauth2-secret},
     * because both are OAuth2 client_credentials and the real axis is how the
     * client authenticates.
     *
     * <p>A document written against the old names bypasses any schema migration
     * entirely, so the import normalises them itself. Without this, those
     * endpoints would install a profile no provider answers to and fail at the
     * first federated query — "No outbound auth provider for profile 'smart'" —
     * rather than at import.
     */
    @Test
    @DisplayName("an import rewrites pre-rename profile names")
    void importMigratesLegacyProfileNames() {
        registry.importRegistry(List.of(), List.of(), List.of(
                endpointDocument("sec-ep", Map.of("authProfile", "smart")),
                endpointDocument("sec-ep2", Map.of("authProfile", "oauth2"))), List.of());

        assertThat(endpoints.findById("sec-ep")).get()
                .satisfies(e -> assertThat(e.authProfile()).isEqualTo(OAUTH2_JWT));
        assertThat(endpoints.findById("sec-ep2")).get()
                .satisfies(e -> assertThat(e.authProfile()).isEqualTo(OAUTH2_SECRET));
    }

    @Test
    @DisplayName("an import carries the non-secret credential fields between deployments")
    void importCarriesNonSecretCredentialFields() {
        registry.importRegistry(List.of(), List.of(), List.of(
                endpointDocument("sec-ep", Map.of(
                        "authProfile", OAUTH2_SECRET,
                        "oauthClientId", "imported-client",
                        "oauthTokenEndpoint", "https://as.test/token",
                        "oauthAuthMethod", Endpoint.AUTH_METHOD_POST))), List.of());

        assertThat(endpoints.findById("sec-ep")).get().satisfies(e -> {
            assertThat(e.oauthClientId()).isEqualTo("imported-client");
            assertThat(e.oauthTokenEndpoint()).isEqualTo("https://as.test/token");
            assertThat(e.oauthAuthMethod()).isEqualTo(Endpoint.AUTH_METHOD_POST);
            assertThat(e.oauthClientSecretEnc())
                    .as("but never a secret: the document does not carry one")
                    .isNull();
        });
    }

    // ---- helpers -------------------------------------------------------------

    private void createEndpointWithSecret(String secret) {
        registry.createEndpoint("sec-ep", "sec-node", "http://localhost:9/openehr", null,
                OAUTH2_SECRET, "active",
                new OutboundAuthSpec(null, null, null, null,
                        "sec-client", "https://as.test/token", secret, null, null, null));
    }

    /** An endpoint as it would arrive in a registry document, bound through Jackson. */
    private Endpoint endpointDocument(String endpointId, Map<String, Object> extra) {
        Map<String, Object> fields = new java.util.HashMap<>(Map.of(
                "endpointId", endpointId,
                "nodeId", "sec-node",
                "baseUrl", "http://localhost:9/openehr",
                "status", "active"));
        fields.putAll(extra);
        return objectMapper.convertValue(fields, Endpoint.class);
    }
}
