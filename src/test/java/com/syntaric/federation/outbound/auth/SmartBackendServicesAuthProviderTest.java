// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.OutboundCredentials;
import com.syntaric.federation.registry.Endpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * SMART Backend Services client-assertion exchange against stub authorization
 * servers — assertion contents, per-endpoint credentials, token caching and
 * identity propagation via the {@code act} claim.
 */
@Tag("CP-16")
@Tag("CP-17")
class SmartBackendServicesAuthProviderTest {

    private static WireMockServer authServerA;
    private static WireMockServer authServerB;
    private static String privateKeyPem;
    private static RSAPrivateKey clientKey;

    @BeforeAll
    static void setUp() throws Exception {
        authServerA = new WireMockServer(wireMockConfig().dynamicPort());
        authServerB = new WireMockServer(wireMockConfig().dynamicPort());
        authServerA.start();
        authServerB.start();
        privateKeyPem = Files.readString(Path.of("src/test/resources/keys/test-private.pem"));
        clientKey = SmartBackendServicesAuthProvider.parsePrivateKey(privateKeyPem);
    }

    @AfterAll
    static void tearDown() {
        authServerA.stop();
        authServerB.stop();
    }

    @BeforeEach
    void reset() {
        authServerA.resetAll();
        authServerB.resetAll();
    }

    private SmartBackendServicesAuthProvider provider() {
        return new SmartBackendServicesAuthProvider(new TokenEndpointDiscovery());
    }

    /** An endpoint with its own SMART credentials — the point of the feature. */
    private EndpointDescriptor endpoint(String id, String clientId, String tokenEndpoint) {
        return new EndpointDescriptor(id, "n1", "cdr1", "Org", "http://node", null, SmartBackendServicesAuthProvider.PROFILE,
                null, null,
                new OutboundCredentials(clientId, tokenEndpoint, privateKeyPem,
                        Endpoint.DEFAULT_SMART_SCOPE,
                        null, null, null, null, null, null));
    }

    private static void stubToken(WireMockServer server, String token) {
        server.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"" + token
                                + "\",\"token_type\":\"bearer\",\"expires_in\":300}")));
    }

    @Test
    void exchangesASignedAssertionForABearerTokenAndPropagatesIdentity() throws Exception {
        stubToken(authServerA, "token-abc");

        String inbound = "Bearer " + clientJwt("dr-alice");
        HttpRequest.Builder request = HttpRequest.newBuilder(java.net.URI.create("http://node/v1/x"));
        provider().apply(request,
                endpoint("ep1", "federation-gateway", authServerA.baseUrl() + "/token"), inbound);

        assertThat(request.build().headers().firstValue("Authorization"))
                .isEqualTo(Optional.of("Bearer token-abc"));

        SignedJWT jwt = SignedJWT.parse(submittedAssertion(authServerA));
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("federation-gateway");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("federation-gateway");
        assertThat(jwt.getJWTClaimsSet().getAudience())
                .containsExactly(authServerA.baseUrl() + "/token");
        // N24: the acting client's identity travels with the exchange
        assertThat(((java.util.Map<?, ?>) jwt.getJWTClaimsSet().getClaim("act")).get("sub"))
                .isEqualTo("dr-alice");
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS384);
    }

    /**
     * The failure this whole feature exists to remove: with one global
     * {@code federation.security.smart.*} block, only one authorization server could
     * ever be registered, and a token minted by it was presented to every node.
     */
    @Test
    @DisplayName("two endpoints use their own authorization servers and get their own tokens")
    void credentialsAreResolvedPerEndpoint() {
        stubToken(authServerA, "token-from-a");
        stubToken(authServerB, "token-from-b");

        SmartBackendServicesAuthProvider provider = provider();
        HttpRequest.Builder toA = HttpRequest.newBuilder(java.net.URI.create("http://a/x"));
        HttpRequest.Builder toB = HttpRequest.newBuilder(java.net.URI.create("http://b/x"));
        provider.apply(toA, endpoint("ep-a", "client-a", authServerA.baseUrl() + "/token"), null);
        provider.apply(toB, endpoint("ep-b", "client-b", authServerB.baseUrl() + "/token"), null);

        assertThat(toA.build().headers().firstValue("Authorization"))
                .isEqualTo(Optional.of("Bearer token-from-a"));
        assertThat(toB.build().headers().firstValue("Authorization"))
                .isEqualTo(Optional.of("Bearer token-from-b"));
        assertThat(authServerA.getAllServeEvents()).hasSize(1);
        assertThat(authServerB.getAllServeEvents()).hasSize(1);
    }

    @Test
    void tokensAreCachedPerEndpointUntilExpiry() {
        stubToken(authServerA, "cached");

        SmartBackendServicesAuthProvider provider = provider();
        EndpointDescriptor ep1 = endpoint("ep1", "client-a", authServerA.baseUrl() + "/token");
        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://n/a")), ep1, null);
        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://n/b")), ep1, null);
        assertThat(authServerA.getAllServeEvents()).hasSize(1);

        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://n/c")),
                endpoint("ep2", "client-a", authServerA.baseUrl() + "/token"), null);
        assertThat(authServerA.getAllServeEvents()).hasSize(2);
    }

    /**
     * Editing a source's credentials must take effect immediately. Keying the
     * cache on the endpoint id alone left the old token live until expiry, so an
     * operator who fixed a misconfiguration saw the same failure and had no way
     * to tell the fix had worked.
     */
    @Test
    @DisplayName("changing an endpoint's credentials invalidates its cached token")
    void aCredentialEditInvalidatesTheCachedToken() {
        stubToken(authServerA, "first");
        SmartBackendServicesAuthProvider provider = provider();
        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://n/a")),
                endpoint("ep1", "old-client", authServerA.baseUrl() + "/token"), null);
        assertThat(authServerA.getAllServeEvents()).hasSize(1);

        // Same endpoint id, different client id: a re-exchange, not a cache hit.
        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://n/b")),
                endpoint("ep1", "new-client", authServerA.baseUrl() + "/token"), null);
        assertThat(authServerA.getAllServeEvents()).hasSize(2);
    }

    @Test
    @DisplayName("missing credentials fail with a message naming the endpoint")
    void missingCredentialsNameTheEndpoint() {
        EndpointDescriptor unconfigured = new EndpointDescriptor("ep-broken", "n1", "cdr1", "Org",
                "http://node", null, SmartBackendServicesAuthProvider.PROFILE, null, null, OutboundCredentials.NONE);

        assertThatThrownBy(() -> provider()
                .apply(HttpRequest.newBuilder(java.net.URI.create("http://n/a")), unconfigured, null))
                .isInstanceOf(IllegalStateException.class)
                // The endpoint, not a property path — there no longer is one.
                .hasMessageContaining("ep-broken")
                .hasMessageContaining("SMART client id");
    }

    @Test
    @DisplayName("the signing key never appears in a descriptor's toString()")
    void credentialsRedactSecretsInToString() {
        String rendered = endpoint("ep1", "client-a", "http://as/token").toString();
        assertThat(rendered).doesNotContain("BEGIN PRIVATE KEY").contains("[REDACTED]");
    }

    private static String submittedAssertion(WireMockServer server) {
        String form = server.getAllServeEvents().get(0).getRequest().getBodyAsString();
        return URLDecoder.decode(form.replaceAll(".*client_assertion=([^&]+).*", "$1"),
                StandardCharsets.UTF_8);
    }

    private String clientJwt(String subject) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),
                new JWTClaimsSet.Builder().subject(subject).build());
        jwt.sign(new RSASSASigner(clientKey));
        return jwt.serialize();
    }
}
