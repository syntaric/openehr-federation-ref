// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds a node's token endpoint from its well-known metadata, when the operator
 * has not pinned one.
 *
 * <p>Two documents, one mechanism: SMART's
 * {@code /.well-known/smart-configuration} and RFC 8414's
 * {@code /.well-known/oauth-authorization-server}. Both answer the same
 * question, so the caching, timeout and failure rules below are shared.
 *
 * <p><b>⚠ Invariant 0 — this is the one part of the feature that adds an HTTP
 * call to the clinical path.</b> Everything about its shape is that constraint:
 *
 * <ul>
 *   <li>It is consulted <b>only</b> when the endpoint's token-endpoint column is
 *       null, so a deployment that pins its endpoints never reaches this code.</li>
 *   <li>Results are cached, and <b>failures are cached too</b>. Negative caching
 *       is not an optimisation here: without it, a node that does not publish
 *       the document would be hit once per token refresh, forever, and each miss
 *       would add its full timeout to a clinical request.</li>
 *   <li>One attempt, one short timeout, <b>no inline retry</b>. A retry inside a
 *       federated request spends the fan-out budget on a call the operator can
 *       make unnecessary by filling in a form field.</li>
 * </ul>
 *
 * <p>A failure therefore degrades to the same {@code IllegalStateException} an
 * unconfigured token endpoint already produces — the clinical path sees the
 * shape of error it saw before this feature existed, not a new one.
 */
@Component
public class TokenEndpointDiscovery {

    private static final Logger log = LoggerFactory.getLogger(TokenEndpointDiscovery.class);

    public static final String SMART_DOCUMENT = "/.well-known/smart-configuration";
    public static final String OAUTH_DOCUMENT = "/.well-known/oauth-authorization-server";

    /**
     * Long enough that a node's metadata is fetched once per deployment-day
     * rather than once per token, short enough that an authorization server
     * migration is picked up without a restart.
     */
    private static final Duration POSITIVE_TTL = Duration.ofHours(1);
    /**
     * Deliberately short. A node that does not publish the document is the
     * common case for RFC 8414, and this window is what keeps that from costing
     * a timeout per token refresh — but it must also let an operator who has
     * just deployed the document see it work without waiting an hour.
     */
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CachedDiscovery> cache = new ConcurrentHashMap<>();

    private record CachedDiscovery(String tokenEndpoint, String failure, Instant expiresAt) {

        boolean fresh() {
            return expiresAt.isAfter(Instant.now());
        }
    }

    /**
     * @param baseUrl  the node's openEHR base URL — metadata is served from its
     *                 origin, not from the API path
     * @param document {@link #SMART_DOCUMENT} or {@link #OAUTH_DOCUMENT}
     * @return the discovered {@code token_endpoint}
     * @throws IllegalStateException naming the endpoint and the document that
     *         was tried. Which document matters: for RFC 8414 the overwhelmingly
     *         likely cause is that the node simply does not publish one, and the
     *         operator needs to be told to fill the field in — not sent to debug
     *         an authorization server that is working fine.
     */
    public String discover(final String endpointId, final String baseUrl, final String document) {
        final String cacheKey = endpointId + "|" + baseUrl + "|" + document;
        CachedDiscovery cached = cache.get(cacheKey);
        if (cached == null || !cached.fresh()) {
            cached = fetch(baseUrl, document);
            cache.put(cacheKey, cached);
        }
        if (cached.tokenEndpoint() == null) {
            throw new IllegalStateException("Endpoint '" + endpointId
                    + "' has no token endpoint configured and discovery via " + document
                    + " failed (" + cached.failure() + "). Set the token endpoint explicitly "
                    + "on the source if the node does not publish this document.");
        }
        return cached.tokenEndpoint();
    }

    private CachedDiscovery fetch(final String baseUrl, final String document) {
        try {
            final URI target = wellKnown(baseUrl, document);
            final HttpRequest request = HttpRequest.newBuilder(target)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            final HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return failed("HTTP " + response.statusCode() + " from " + target);
            }
            final JsonNode root = objectMapper.readTree(response.body());
            final JsonNode tokenEndpoint = root.get("token_endpoint");
            if (tokenEndpoint == null || !tokenEndpoint.isTextual() || tokenEndpoint.asText().isBlank()) {
                return failed("no token_endpoint in " + target);
            }
            return new CachedDiscovery(tokenEndpoint.asText(), null,
                    Instant.now().plus(POSITIVE_TTL));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            // Not cached: an interrupt says something about this thread, not
            // about the node, and caching it would punish the next request.
            return new CachedDiscovery(null, "interrupted", Instant.now());
        } catch (final Exception e) {
            log.debug("Token endpoint discovery failed for {}{}", baseUrl, document, e);
            return failed(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private static CachedDiscovery failed(final String reason) {
        return new CachedDiscovery(null, reason, Instant.now().plus(NEGATIVE_TTL));
    }

    /**
     * Well-known URIs live at the <b>origin</b> (RFC 8615), so a base URL of
     * {@code https://cdr.example/openehr/v1} resolves to
     * {@code https://cdr.example/.well-known/...} — not to a path underneath the
     * API root, where nothing serves them.
     */
    static URI wellKnown(final String baseUrl, final String document) {
        final URI base = URI.create(baseUrl);
        final String port = base.getPort() == -1 ? "" : ":" + base.getPort();
        return URI.create(base.getScheme() + "://" + base.getHost() + port + document);
    }
}
