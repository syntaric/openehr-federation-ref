// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.outbound.auth.OutboundAuthProviders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The gateway's own thin client for member CDR nodes (no SDK client module).
 * Built on the JDK {@link HttpClient} so the per-request timeout can track the
 * remaining fan-out budget exactly.
 */
public class NodeClient {

    private final EndpointDescriptor endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OutboundAuthProviders authProviders;

    NodeClient(final EndpointDescriptor endpoint, final HttpClient httpClient, final ObjectMapper objectMapper,
               final OutboundAuthProviders authProviders) {
        this.endpoint = endpoint;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.authProviders = authProviders;
    }

    public EndpointDescriptor endpoint() {
        return endpoint;
    }

    public String queryUrl() {
        return endpoint.url() + "/v1/query/aql";
    }

    /**
     * Dispatches standard AQL to the node. Throws
     * {@link java.net.http.HttpTimeoutException} on per-node timeout and
     * {@link IOException} on connection failure — the fan-out executor maps
     * those to {@code time-out} / {@code offline}.
     */
    public NodeQueryResponse query(final String aql, final Duration timeout, final String inboundAuthorization)
            throws IOException, InterruptedException {
        final byte[] body = objectMapper.writeValueAsBytes(Map.of("q", aql));
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(queryUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        authProviders.forEndpoint(endpoint).apply(request, endpoint, inboundAuthorization);

        final HttpResponse<byte[]> response =
                httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Node returned HTTP " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), NodeQueryResponse.class);
    }

    /** Lightweight existence probe: {@code GET {base}/v1/ehr/{ehr_id}} (ask-all, reads only). */
    public boolean probeEhr(final String ehrId, final Duration timeout, final String inboundAuthorization) {
        try {
            final HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create(endpoint.url() + "/v1/ehr/" + ehrId))
                    .header("Accept", "application/json")
                    .timeout(timeout)
                    .GET();
            authProviders.forEndpoint(endpoint).apply(request, endpoint, inboundAuthorization);
            final HttpResponse<Void> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (final IOException e) {
            return false;
        }
    }

    /** openEHR ITS-REST query response as returned by a node: rows are arrays. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeQueryResponse(String q, List<Column> columns, List<List<Object>> rows) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Column(String name, String path) {
        }

        public List<Column> columnsOrEmpty() {
            return columns == null ? List.of() : columns;
        }

        public List<List<Object>> rowsOrEmpty() {
            return rows == null ? List.of() : rows;
        }
    }

    public static Optional<String> bearerOf(final String authorizationHeader) {
        return Optional.ofNullable(authorizationHeader);
    }
}
