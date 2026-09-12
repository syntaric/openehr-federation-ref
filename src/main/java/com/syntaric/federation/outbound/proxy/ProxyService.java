// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.proxy;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.FederationHeaders;
import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.outbound.NodeClientFactory;
import com.syntaric.federation.outbound.auth.OutboundAuthProviders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * Byte-identical single-node pass-through (plan decision #3, N22/N31): the
 * request and response bodies are streamed raw — no message converters, no
 * content rewriting — only hop-by-hop headers are stripped and the client's
 * Authorization header is replaced by the endpoint's outbound auth profile.
 * {@code Location} and {@code ETag} pass through unmodified.
 */
@Service
public class ProxyService {

    private final HttpClient httpClient;
    private final OutboundAuthProviders authProviders;
    private final FederationProperties properties;

    public ProxyService(final NodeClientFactory clientFactory, final OutboundAuthProviders authProviders,
                        final FederationProperties properties) {
        this.httpClient = clientFactory.httpClient();
        this.authProviders = authProviders;
        this.properties = properties;
    }

    /** Forwards the request to {@code endpoint.url() + pathWithinBase} and streams back the answer. */
    public ProxyResult forward(final HttpServletRequest request, final HttpServletResponse response,
                               final EndpointDescriptor endpoint, final String pathWithinBase) throws IOException {
        return forward(request, response, endpoint, pathWithinBase, r -> {
        });
    }

    /**
     * Variant with a hook invoked after the node answered but BEFORE the body is
     * streamed to the client — side effects (registry learning) must be durable
     * before the client can observe the response.
     */
    public ProxyResult forward(final HttpServletRequest request, final HttpServletResponse response,
                               final EndpointDescriptor endpoint, final String pathWithinBase,
                               final java.util.function.Consumer<ProxyResult> beforeStreaming)
            throws IOException {
        final String query = request.getQueryString();
        final URI target = URI.create(endpoint.url() + pathWithinBase + (query != null ? "?" + query : ""));

        final HttpRequest.Builder outbound = HttpRequest.newBuilder(target)
                .timeout(properties.timeouts().perNode());
        copyRequestHeaders(request, outbound);

        final byte[] body = request.getInputStream().readAllBytes();
        outbound.method(request.getMethod(),
                body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        authProviders.forEndpoint(endpoint).apply(outbound, endpoint, request.getHeader("Authorization"));

        HttpResponse<InputStream> nodeResponse;
        try {
            nodeResponse = httpClient.send(outbound.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FederationException(FedErrorCode.FED_UPSTREAM_ERROR, "Proxy interrupted");
        } catch (final IOException e) {
            throw new FederationException(FedErrorCode.FED_UPSTREAM_ERROR,
                    "Node " + endpoint.endpointId() + " is unreachable");
        }

        final ProxyResult result = new ProxyResult(nodeResponse.statusCode(),
                nodeResponse.headers().firstValue("ETag").orElse(null),
                nodeResponse.headers().firstValue("Location").orElse(null));
        beforeStreaming.accept(result);

        response.setStatus(nodeResponse.statusCode());
        for (final Map.Entry<String, List<String>> header : nodeResponse.headers().map().entrySet()) {
            if (HopByHopHeaders.isHopByHop(header.getKey()) || header.getKey().startsWith(":")) {
                continue;
            }
            for (final String value : header.getValue()) {
                response.addHeader(header.getKey(), value);
            }
        }
        response.setHeader(FederationHeaders.ENDPOINT, endpoint.endpointId());
        if (endpoint.systemId() != null) {
            response.setHeader(FederationHeaders.SYSTEM_ID, endpoint.systemId());
        }

        try (final InputStream in = nodeResponse.body(); final OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
        return result;
    }

    private void copyRequestHeaders(final HttpServletRequest request, final HttpRequest.Builder outbound) {
        final Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            final String name = names.nextElement();
            final String lower = name.toLowerCase();
            if (HopByHopHeaders.isHopByHop(name)
                    || "authorization".equals(lower)
                    || lower.startsWith("openehr-federation-")) {
                continue;
            }
            final Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                outbound.header(name, values.nextElement());
            }
        }
    }

    public record ProxyResult(int status, String etag, String location) {
    }
}
