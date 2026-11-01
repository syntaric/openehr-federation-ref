// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * Per-request federation context parsed from the {@code openEHR-federation-*}
 * and {@code Prefer} headers, plus the identity of the request itself.
 *
 * <p>Two identifiers, on purpose. {@code requestId} is minted by the gateway for
 * every request and is the key anything recorded about it hangs off; it is
 * never taken from the client, so nothing a caller sends can collide with or
 * overwrite another request's record. {@code correlationId} is what the
 * <i>client</i> knows the request as: the {@code X-Request-ID} it sent, if it
 * sent a usable one, and otherwise the {@code requestId} itself. It is echoed
 * back on the response, so the value a caller sees in its own tooling is the
 * one an operator can search for.
 */
public record FederationRequestContext(
        LinkedHashSet<String> headerEndpointIds,
        LinkedHashSet<String> headerOrganisationIds,
        Completeness completeness,
        String dedupMode,
        Duration preferWait,
        UUID requestId,
        String correlationId) {

    /**
     * §11.4 completion strategy. {@link #ALL} — all-or-nothing — is the default
     * and needs no header; {@link #PARTIAL} is the opt-in best-effort mode a
     * client selects with {@code openEHR-federation-completeness: partial}.
     */
    public enum Completeness { ALL, PARTIAL }

    public static final String REQUEST_ATTRIBUTE = FederationRequestContext.class.getName();

    public FederationRequestContext {
        requestId = requestId == null ? UUID.randomUUID() : requestId;
        correlationId = correlationId == null || correlationId.isBlank() ? requestId.toString() : correlationId;
    }

    public static FederationRequestContext empty() {
        return new FederationRequestContext(new LinkedHashSet<>(), new LinkedHashSet<>(),
                Completeness.ALL, FederationHeaders.DEDUP_NONE, null, null, null);
    }

    public boolean hasHeaderTargets() {
        return !headerEndpointIds.isEmpty() || !headerOrganisationIds.isEmpty();
    }
}
