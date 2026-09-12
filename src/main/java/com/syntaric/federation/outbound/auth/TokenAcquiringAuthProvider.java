// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.OutboundCredentials;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The half that SMART Backend Services and plain OAuth2 client credentials have
 * in common: POST a form to the source's configured token endpoint, read
 * {@code access_token}/{@code expires_in}, cache until close to expiry, set the
 * {@code Authorization} header.
 *
 * <p>Subclasses supply only what actually differs — the form body and how the
 * client authenticates itself. Extracted rather than copied because the two
 * grants differ in about fifteen lines out of a hundred and sixty, and the
 * copied version of the caching logic is the one that would quietly stop
 * matching.
 */
abstract class TokenAcquiringAuthProvider implements OutboundAuthProvider {

    private final TokenEndpointDiscovery discovery;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    protected TokenAcquiringAuthProvider(final TokenEndpointDiscovery discovery) {
        this.discovery = discovery;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {

        /** 30s of slack, so a token cannot expire in flight between here and the node. */
        boolean fresh() {
            return expiresAt.isAfter(Instant.now().plusSeconds(30));
        }
    }

    /** The prepared token request: where to post, what form, and any extra headers. */
    protected record TokenRequest(String tokenEndpoint, String form, Map<String, String> headers) {

        protected TokenRequest(final String tokenEndpoint, final String form) {
            this(tokenEndpoint, form, Map.of());
        }
    }

    @Override
    public void apply(final HttpRequest.Builder request, final EndpointDescriptor endpoint,
                      final String inboundAuthorization) {
        final CachedToken token = cache.compute(cacheKey(endpoint), (key, existing) ->
                existing != null && existing.fresh() ? existing : exchange(endpoint, inboundAuthorization));
        if (token != null) {
            request.header("Authorization", "Bearer " + token.accessToken());
        }
    }

    /**
     * Endpoint id <b>plus a digest of the credentials</b>.
     *
     * <p>Keying on the id alone — what this did before credentials were
     * per-endpoint — meant an operator who corrected a source's credentials kept
     * getting the old token until it expired: they fix the problem, re-test, and
     * see the same failure, with nothing to tell them the fix was fine. Folding
     * the credentials into the key makes an edit evict the entry by construction.
     */
    private String cacheKey(final EndpointDescriptor endpoint) {
        return endpoint.endpointId() + "|" + endpoint.credentials().fingerprint();
    }

    private CachedToken exchange(final EndpointDescriptor endpoint, final String inboundAuthorization) {
        final TokenRequest prepared = prepareRequest(endpoint, inboundAuthorization);
        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(prepared.tokenEndpoint()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(prepared.form()));
            prepared.headers().forEach(builder::header);

            final HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // The status code, never the body: an authorization server may
                // echo submitted parameters in an error response, and this
                // message travels into logs and probe results.
                throw new IllegalStateException("Token endpoint for endpoint '"
                        + endpoint.endpointId() + "' returned HTTP " + response.statusCode());
            }
            final TokenResponse body = objectMapper.readValue(response.body(), TokenResponse.class);
            if (body.accessToken() == null || body.accessToken().isBlank()) {
                throw new IllegalStateException("Token endpoint for endpoint '"
                        + endpoint.endpointId() + "' returned no access_token");
            }
            // The detail carries the endpoint and the lifetime — never the
            // token, and never any credential.
            return new CachedToken(body.accessToken(),
                    Instant.now().plusSeconds(body.expiresIn() > 0 ? body.expiresIn() : 300));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(profile() + " token exchange interrupted for endpoint '"
                    + endpoint.endpointId() + "'", e);
        } catch (final IllegalStateException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(profile() + " token exchange failed for endpoint '"
                    + endpoint.endpointId() + "': " + e.getMessage(), e);
        }
    }

    /**
     * The configured token endpoint, or — only when the source has none — the one
     * published in the node's well-known metadata.
     *
     * <p>A set value wins outright and no metadata call is made: it means the
     * operator decided, and a deployment that pins its endpoints must not pay for
     * an HTTP call on the clinical path. See {@link TokenEndpointDiscovery} for
     * why the fallback caches its failures.
     */
    protected String requireTokenEndpoint(final EndpointDescriptor endpoint, final String configured) {
        if (isSet(configured)) {
            return configured;
        }
        return discovery.discover(endpoint.endpointId(), endpoint.url(), wellKnownDocument());
    }

    /**
     * The well-known document this profile's authorization server publishes —
     * {@link TokenEndpointDiscovery#SMART_DOCUMENT} or
     * {@link TokenEndpointDiscovery#OAUTH_DOCUMENT}.
     */
    protected abstract String wellKnownDocument();

    /** Builds the grant-specific request. Runs outside the cache lock's hot path work where possible. */
    protected abstract TokenRequest prepareRequest(final EndpointDescriptor endpoint,
                                                   final String inboundAuthorization);

    /** Fails with a message naming the <b>endpoint</b>, not a property path — there no longer is one. */
    protected static void require(final boolean condition, final EndpointDescriptor endpoint, final String what) {
        if (!condition) {
            throw new IllegalStateException("Endpoint '" + endpoint.endpointId()
                    + "' uses an outbound auth profile that requires " + what
                    + ", which is not configured on the source.");
        }
    }

    protected static OutboundCredentials credentials(final EndpointDescriptor endpoint) {
        return endpoint.credentials();
    }

    protected static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) {
    }
}
