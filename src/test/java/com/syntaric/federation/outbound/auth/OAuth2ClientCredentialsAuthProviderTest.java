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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Plain OAuth2 client-credentials exchange against a stub authorization server:
 * both client authentication methods, the optional parameters, and the encoding
 * rule that only bites for some secrets.
 */
class OAuth2ClientCredentialsAuthProviderTest {

    private static WireMockServer authServer;

    @BeforeAll
    static void start() {
        authServer = new WireMockServer(wireMockConfig().dynamicPort());
        authServer.start();
    }

    @AfterAll
    static void stop() {
        authServer.stop();
    }

    @BeforeEach
    void stubToken() {
        authServer.resetAll();
        authServer.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"oauth-token\",\"expires_in\":300}")));
    }

    private OAuth2ClientCredentialsAuthProvider provider() {
        return new OAuth2ClientCredentialsAuthProvider(new TokenEndpointDiscovery());
    }

    private EndpointDescriptor endpoint(String id, String clientId, String secret,
                                        String scope, String authMethod, String audience) {
        return new EndpointDescriptor(id, "n1", "cdr1", "Org", "http://node", null, OAuth2ClientCredentialsAuthProvider.PROFILE,
                null, null,
                new OutboundCredentials(null, null, null, null,
                        clientId, authServer.baseUrl() + "/token", secret, scope,
                        authMethod == null ? Endpoint.AUTH_METHOD_BASIC : authMethod, audience));
    }

    private String submittedForm() {
        return authServer.getAllServeEvents().get(0).getRequest().getBodyAsString();
    }

    private Optional<String> submittedAuthorization() {
        return Optional.ofNullable(authServer.getAllServeEvents().get(0).getRequest()
                .getHeader("Authorization"));
    }

    private void exchange(EndpointDescriptor endpoint) {
        provider().apply(HttpRequest.newBuilder(java.net.URI.create("http://node/x")), endpoint, null);
    }

    @Test
    @DisplayName("client_secret_basic sends the credentials in an Authorization header")
    void basicAuthPutsCredentialsInTheHeader() {
        HttpRequest.Builder request = HttpRequest.newBuilder(java.net.URI.create("http://node/x"));
        provider().apply(request,
                endpoint("ep1", "client-a", "secret-a", null, Endpoint.AUTH_METHOD_BASIC, null), null);

        assertThat(request.build().headers().firstValue("Authorization"))
                .isEqualTo(Optional.of("Bearer oauth-token"));
        assertThat(submittedAuthorization())
                .contains("Basic " + Base64.getEncoder()
                        .encodeToString("client-a:secret-a".getBytes(StandardCharsets.UTF_8)));
        assertThat(submittedForm()).contains("grant_type=client_credentials")
                .doesNotContain("client_secret=");
    }

    @Test
    @DisplayName("client_secret_post sends them in the form body instead")
    void postAuthPutsCredentialsInTheBody() {
        exchange(endpoint("ep1", "client-a", "secret-a", null, Endpoint.AUTH_METHOD_POST, null));

        assertThat(submittedAuthorization()).isEmpty();
        assertThat(submittedForm())
                .contains("client_id=client-a")
                .contains("client_secret=secret-a");
    }

    /**
     * RFC 6749 §2.3.1 requires form-urlencoding both halves before base64. Skip
     * it and a secret containing {@code +} or {@code /} still authenticates
     * against a lenient AS and fails against a conformant one — which reads as
     * the node's fault, not ours.
     */
    @Test
    @DisplayName("a secret containing + and / survives basic-auth encoding")
    void basicAuthEncodesReservedCharacters() {
        exchange(endpoint("ep1", "client+a", "se/cr+et=", null, Endpoint.AUTH_METHOD_BASIC, null));

        String header = submittedAuthorization().orElseThrow();
        String decoded = new String(Base64.getDecoder()
                .decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("client%2Ba:se%2Fcr%2Bet%3D");
    }

    @Test
    @DisplayName("scope and audience are sent when set")
    void optionalParametersAreSentWhenSet() {
        exchange(endpoint("ep1", "c", "s", "read:records", null, "https://api.node.test"));

        assertThat(submittedForm())
                .contains("scope=read%3Arecords")
                .contains("audience=https%3A%2F%2Fapi.node.test");
    }

    /**
     * There is no safe default scope for a plain OAuth2 AS: a guess yields a
     * token the AS issues happily and the node then refuses, which surfaces far
     * from its cause.
     */
    @Test
    @DisplayName("scope and audience are omitted entirely when unset, never sent empty")
    void optionalParametersAreOmittedWhenUnset() {
        exchange(endpoint("ep1", "c", "s", null, null, null));

        assertThat(submittedForm())
                .isEqualTo("grant_type=client_credentials");
    }

    @Test
    @DisplayName("missing credentials fail with a message naming the endpoint")
    void missingCredentialsNameTheEndpoint() {
        EndpointDescriptor unconfigured = new EndpointDescriptor("ep-broken", "n1", "cdr1", "Org",
                "http://node", null, OAuth2ClientCredentialsAuthProvider.PROFILE, null, null, OutboundCredentials.NONE);

        assertThatThrownBy(() -> exchange(unconfigured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ep-broken")
                .hasMessageContaining("OAuth2 client id");
    }

    @Test
    @DisplayName("a 401 from the authorization server reports the status, never the request body")
    void rejectedCredentialsDoNotLeakTheRequest() {
        authServer.resetAll();
        authServer.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid_client\"}")));

        assertThatThrownBy(() -> exchange(endpoint("ep1", "c", "top-secret", null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("top-secret");
    }

    @Test
    @DisplayName("tokens are cached per endpoint")
    void tokensAreCached() {
        OAuth2ClientCredentialsAuthProvider provider = provider();
        EndpointDescriptor ep = endpoint("ep1", "c", "s", null, null, null);
        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://n/a")), ep, null);
        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://n/b")), ep, null);

        assertThat(authServer.getAllServeEvents()).hasSize(1);
    }

    @Test
    @DisplayName("changing the client secret invalidates the cached token")
    void aCredentialEditInvalidatesTheCachedToken() {
        OAuth2ClientCredentialsAuthProvider provider = provider();
        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://n/a")),
                endpoint("ep1", "c", "old-secret", null, null, null), null);
        provider.apply(HttpRequest.newBuilder(java.net.URI.create("http://n/b")),
                endpoint("ep1", "c", "new-secret", null, null, null), null);

        assertThat(authServer.getAllServeEvents()).hasSize(2);
    }
}
