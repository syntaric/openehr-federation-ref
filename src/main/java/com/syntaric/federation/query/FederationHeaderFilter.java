// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.config.FederationProperties;
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
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses the {@code openEHR-federation-*} and {@code Prefer} headers into a
 * {@link FederationRequestContext} request attribute and rejects malformed
 * values early with 400. Targeting offered as a query parameter
 * ({@code ?endpoint=}/{@code ?organisation=}) is not a supported mechanism
 * (N35) and is rejected outright.
 *
 * <p>Also assigns the request its identity (see {@link FederationRequestContext})
 * and echoes the correlation id as {@code X-Request-ID} on every {@code /v1}
 * response — before the chain runs, so a 400 from here or a 401 from the
 * security filters carries it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FederationHeaderFilter extends OncePerRequestFilter {

    public static final int MAX_CORRELATION_ID_LENGTH = 64;
    private static final Pattern CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_CORRELATION_ID_LENGTH + "}");

    private final FederationProperties properties;

    public FederationHeaderFilter(final FederationProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1");
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        // Decided and echoed before anything else can answer, so the header is on
        // the response whether the request succeeds, fails validation below, or is
        // refused by the security chain further down.
        final UUID requestId = UUID.randomUUID();
        final String correlationId = correlationId(request, requestId);
        response.setHeader(FederationHeaders.REQUEST_ID, correlationId);
        try {
            final FederationRequestContext context = parse(request, requestId, correlationId);
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

    /**
     * The client's {@code X-Request-ID} when it is one printable token of a
     * sensible length; otherwise the gateway's own id. The constraint is what
     * makes the value safe to echo as a header and to store and search as-is —
     * an unusable value is ignored rather than rejected, because a correlation
     * aid must never be a reason to refuse a clinical request.
     */
    private static String correlationId(final HttpServletRequest request, final UUID requestId) {
        final String supplied = request.getHeader(FederationHeaders.REQUEST_ID);
        if (supplied == null) {
            return requestId.toString();
        }
        final String trimmed = supplied.trim();
        return CORRELATION_ID.matcher(trimmed).matches() ? trimmed : requestId.toString();
    }

    private FederationRequestContext parse(final HttpServletRequest request, final UUID requestId,
                                           final String correlationId) {
        if (request.getParameter("endpoint") != null || request.getParameter("organisation") != null) {
            throw new FederationException(FedErrorCode.FED_HEADER_INVALID,
                    "Endpoint targeting must use the " + FederationHeaders.ENDPOINT
                            + " header, never a query parameter (N35)");
        }

        final LinkedHashSet<String> endpointIds = splitList(request, FederationHeaders.ENDPOINT);
        final LinkedHashSet<String> organisationIds = splitList(request, FederationHeaders.ORGANISATION);

        final FederationRequestContext.Completeness completeness =
                parseCompleteness(request.getHeader(FederationHeaders.COMPLETENESS));

        final String dedupHeader = request.getHeader(FederationHeaders.DEDUP);
        final String dedupMode = dedupHeader == null
                ? FederationHeaders.DEDUP_NONE
                : dedupHeader.trim().toLowerCase(Locale.ROOT);
        if (!FederationHeaders.DEDUP_NONE.equals(dedupMode)
                && !FederationHeaders.DEDUP_VERSION_IDENTITY.equals(dedupMode)) {
            throw new FederationException(FedErrorCode.FED_HEADER_INVALID,
                    "Unsupported " + FederationHeaders.DEDUP + " mode '" + dedupMode + "'");
        }

        return new FederationRequestContext(endpointIds, organisationIds, completeness, dedupMode,
                parsePreferWait(request), requestId, correlationId);
    }

    /**
     * §11.4: {@code all} is the default and MUST be accepted explicitly;
     * {@code partial} selects best-effort where the deployment offers it, and a
     * gateway that does not offer it MUST reject the header rather than ignore
     * it — silently answering all-or-nothing to a client that asked for partial
     * would be a surprise in the safe direction, but still a surprise.
     */
    private FederationRequestContext.Completeness parseCompleteness(final String header) {
        if (header == null) {
            return FederationRequestContext.Completeness.ALL;
        }
        final String value = header.trim().toLowerCase(Locale.ROOT);
        if (FederationHeaders.COMPLETENESS_ALL.equals(value)) {
            return FederationRequestContext.Completeness.ALL;
        }
        if (FederationHeaders.COMPLETENESS_PARTIAL.equals(value)) {
            if (!properties.completeness().offerBestEffort()) {
                throw new FederationException(FedErrorCode.FED_HEADER_INVALID,
                        "Best-effort completeness is not offered by this federation; omit the "
                                + FederationHeaders.COMPLETENESS + " header or send 'all'");
            }
            return FederationRequestContext.Completeness.PARTIAL;
        }
        throw new FederationException(FedErrorCode.FED_HEADER_INVALID,
                "Unsupported " + FederationHeaders.COMPLETENESS + " value; 'all' and 'partial' are defined");
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
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("wait=")) {
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
