// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.OutboundCredentials;
import com.syntaric.federation.registry.Endpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Token endpoint discovery — the one part of this feature that adds an HTTP call
 * to the clinical path, so the properties pinned here are the Invariant 0 ones:
 * an explicit value skips discovery entirely, and a failure is cached rather
 * than repeated per token refresh.
 */
class TokenEndpointDiscoveryTest {

    private static WireMockServer node;

    @BeforeAll
    static void start() {
        node = new WireMockServer(wireMockConfig().dynamicPort());
        node.start();
    }

    @AfterAll
    static void stop() {
        node.stop();
    }

    @BeforeEach
    void reset() {
        node.resetAll();
        node.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"discovered-token\",\"expires_in\":300}")));
    }

    private void stubWellKnown(String document) {
        node.stubFor(get(urlPathEqualTo(document))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token_endpoint\":\"" + node.baseUrl() + "/token\"}")));
    }

    /** An OAuth2 endpoint with no pinned token endpoint, so discovery applies. */
    private EndpointDescriptor oauthEndpoint(String id, String pinnedTokenEndpoint) {
        return new EndpointDescriptor(id, "n1", "cdr1", "Org", node.baseUrl() + "/openehr/v1",
                null, "oauth2", null, null,
                new OutboundCredentials(null, null, null, null,
                        "client-a", pinnedTokenEndpoint, "secret-a", null,
                        Endpoint.AUTH_METHOD_BASIC, null));
    }

    private OAuth2ClientCredentialsAuthProvider provider() {
        return new OAuth2ClientCredentialsAuthProvider(new TokenEndpointDiscovery());
    }

    private void exchange(OAuth2ClientCredentialsAuthProvider provider, EndpointDescriptor endpoint) {
        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://node/x")), endpoint, null);
    }

    @Test
    @DisplayName("the token endpoint is discovered from the well-known document and used")
    void discoversAndUsesTheTokenEndpoint() {
        stubWellKnown(TokenEndpointDiscovery.OAUTH_DOCUMENT);

        exchange(provider(), oauthEndpoint("ep1", null));

        node.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo(TokenEndpointDiscovery.OAUTH_DOCUMENT)));
        node.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlPathEqualTo("/token")));
    }

    /**
     * A set column means "the operator decided this". Consulting the node's
     * metadata anyway would let it silently override an explicit deployment
     * decision — and would put an HTTP call on the clinical path for every
     * deployment, including the ones that pinned their endpoints precisely to
     * avoid one.
     */
    @Test
    @DisplayName("an explicit token endpoint wins and no well-known call is made")
    void anExplicitValueSkipsDiscoveryEntirely() {
        stubWellKnown(TokenEndpointDiscovery.OAUTH_DOCUMENT);

        exchange(provider(), oauthEndpoint("ep1", node.baseUrl() + "/token"));

        node.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo(TokenEndpointDiscovery.OAUTH_DOCUMENT)));
        node.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlPathEqualTo("/token")));
    }

    @Test
    @DisplayName("well-known documents are read from the origin, not under the API path")
    void wellKnownResolvesAtTheOrigin() {
        assertThat(TokenEndpointDiscovery.wellKnown("https://cdr.example/openehr/v1",
                TokenEndpointDiscovery.SMART_DOCUMENT))
                .hasToString("https://cdr.example" + TokenEndpointDiscovery.SMART_DOCUMENT);
        assertThat(TokenEndpointDiscovery.wellKnown("http://cdr.example:8080/openehr",
                TokenEndpointDiscovery.OAUTH_DOCUMENT))
                .hasToString("http://cdr.example:8080" + TokenEndpointDiscovery.OAUTH_DOCUMENT);
    }

    /**
     * Most CDRs do not publish RFC 8414. The error has to say which document was
     * tried, so the operator fills in the field rather than going off to debug
     * an authorization server that is working fine.
     */
    @Test
    @DisplayName("a 404 names the endpoint and the document that was tried")
    void aMissingDocumentProducesAnEndpointNamedError() {
        node.stubFor(get(urlPathEqualTo(TokenEndpointDiscovery.OAUTH_DOCUMENT))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> exchange(provider(), oauthEndpoint("ep-a", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ep-a")
                .hasMessageContaining(TokenEndpointDiscovery.OAUTH_DOCUMENT)
                .hasMessageContaining("Set the token endpoint explicitly");
    }

    /**
     * Invariant 0. Without negative caching a node that does not publish the
     * document is hit once per token refresh, forever, each miss adding its full
     * timeout to a clinical request.
     */
    @Test
    @DisplayName("a discovery failure is cached, so it is not retried on the next request")
    void failuresAreNegativelyCached() {
        node.stubFor(get(urlPathEqualTo(TokenEndpointDiscovery.OAUTH_DOCUMENT))
                .willReturn(aResponse().withStatus(404)));

        // One provider, so one discovery cache — as in the running application,
        // where the provider is a singleton.
        OAuth2ClientCredentialsAuthProvider provider = provider();
        EndpointDescriptor endpoint = oauthEndpoint("ep-a", null);
        assertThatThrownBy(() -> exchange(provider, endpoint)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> exchange(provider, endpoint)).isInstanceOf(IllegalStateException.class);

        node.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo(TokenEndpointDiscovery.OAUTH_DOCUMENT)));
    }

    @Test
    @DisplayName("a successful discovery is cached too")
    void successesAreCached() {
        stubWellKnown(TokenEndpointDiscovery.OAUTH_DOCUMENT);

        OAuth2ClientCredentialsAuthProvider provider = provider();
        // Two endpoint ids so the *token* cache misses and discovery is consulted
        // again — otherwise a cached token would hide whether discovery repeated.
        exchange(provider, oauthEndpoint("ep-a", null));
        exchange(provider, oauthEndpoint("ep-a", null));

        node.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo(TokenEndpointDiscovery.OAUTH_DOCUMENT)));
    }

    @Test
    @DisplayName("a document without token_endpoint is a failure, not a null endpoint")
    void aDocumentMissingTheFieldFails() {
        node.stubFor(get(urlPathEqualTo(TokenEndpointDiscovery.OAUTH_DOCUMENT))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"issuer\":\"https://as.example\"}")));

        assertThatThrownBy(() -> exchange(provider(), oauthEndpoint("ep-a", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ep-a");
    }

    @Test
    @DisplayName("SMART discovery reads its own document")
    void smartUsesTheSmartConfigurationDocument() throws Exception {
        stubWellKnown(TokenEndpointDiscovery.SMART_DOCUMENT);
        String pem = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/test/resources/keys/test-private.pem"));

        EndpointDescriptor smart = new EndpointDescriptor("ep-s", "n1", "cdr1", "Org",
                node.baseUrl() + "/openehr/v1", null, "smart", null, null,
                new OutboundCredentials("client-a", null, pem, Endpoint.DEFAULT_SMART_SCOPE,
                        null, null, null, null, null, null));

        new SmartBackendServicesAuthProvider(new TokenEndpointDiscovery())
                .apply(HttpRequest.newBuilder(java.net.URI.create("http://node/x")), smart, null);

        node.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo(TokenEndpointDiscovery.SMART_DOCUMENT)));
    }
}
