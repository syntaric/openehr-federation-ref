// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Parses the {@code openEHR-federation-*} and {@code Prefer} headers into a
 * {@link FederationRequestContext} request attribute and rejects malformed
 * values early with 400. Targeting offered as a query parameter
 * ({@code ?endpoint=}/{@code ?organisation=}) is not a supported mechanism
 * (N35) and is rejected outright.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FederationHeaderFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1");
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        try {
            final FederationRequestContext context = parse(request);
            request.setAttribute(FederationRequestContext.REQUEST_ATTRIBUTE, context);
        } catch (final FederationException ex) {
            response.setStatus(ex.code().status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"" + ex.code().name() + "\",\"message\":\""
                    + ex.getMessage().replace("\"", "'") + "\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private FederationRequestContext parse(final HttpServletRequest request) {
        if (request.getParameter("endpoint") != null || request.getParameter("organisation") != null) {
            throw new FederationException(FedErrorCode.FED_HEADER_INVALID,
                    "Endpoint targeting must use the " + FederationHeaders.ENDPOINT
                            + " header, never a query parameter (N35)");
        }

        final LinkedHashSet<String> endpointIds = splitList(request, FederationHeaders.ENDPOINT);
        final LinkedHashSet<String> organisationIds = splitList(request, FederationHeaders.ORGANISATION);

        FederationRequestContext.Completeness completeness = FederationRequestContext.Completeness.BEST_EFFORT;
        final String completenessHeader = request.getHeader(FederationHeaders.COMPLETENESS);
        if (completenessHeader != null) {
            if (!FederationHeaders.COMPLETENESS_ALL.equalsIgnoreCase(completenessHeader.trim())) {
                throw new FederationException(FedErrorCode.FED_HEADER_INVALID,
                        "Unsupported " + FederationHeaders.COMPLETENESS + " value; only 'all' is defined");
            }
            completeness = FederationRequestContext.Completeness.ALL;
        }

        String dedupMode = FederationHeaders.DEDUP_NONE;
        final String dedupHeader = request.getHeader(FederationHeaders.DEDUP);
        if (dedupHeader != null) {
            final String value = dedupHeader.trim().toLowerCase();
            if (!FederationHeaders.DEDUP_NONE.equals(value)
                    && !FederationHeaders.DEDUP_VERSION_IDENTITY.equals(value)) {
                throw new FederationException(FedErrorCode.FED_HEADER_INVALID,
                        "Unsupported " + FederationHeaders.DEDUP + " mode '" + value + "'");
            }
            dedupMode = value;
        }

        return new FederationRequestContext(endpointIds, organisationIds, completeness, dedupMode,
                parsePreferWait(request));
    }

    private LinkedHashSet<String> splitList(final HttpServletRequest request, final String header) {
        final Enumeration<String> values = request.getHeaders(header);
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        while (values != null && values.hasMoreElements()) {
            result.addAll(Arrays.stream(values.nextElement().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        return result;
    }

    private Duration parsePreferWait(final HttpServletRequest request) {
        final Enumeration<String> prefers = request.getHeaders(FederationHeaders.PREFER);
        while (prefers != null && prefers.hasMoreElements()) {
            for (final String token : prefers.nextElement().split("[,;]")) {
                final String trimmed = token.trim();
                if (trimmed.toLowerCase().startsWith("wait=")) {
                    try {
                        final long seconds = Long.parseLong(trimmed.substring("wait=".length()).trim());
                        if (seconds <= 0) {
                            throw new NumberFormatException();
                        }
                        return Duration.ofSeconds(seconds);
                    } catch (final NumberFormatException e) {
                        throw new FederationException(FedErrorCode.FED_HEADER_INVALID,
                                "Malformed Prefer: wait= value");
                    }
                }
            }
        }
        return null;
    }
}
