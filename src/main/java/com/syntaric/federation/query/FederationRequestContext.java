// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import java.time.Duration;
import java.util.LinkedHashSet;

/**
 * Per-request federation context parsed from the {@code openEHR-federation-*}
 * and {@code Prefer} headers.
 */
public record FederationRequestContext(
        LinkedHashSet<String> headerEndpointIds,
        LinkedHashSet<String> headerOrganisationIds,
        Completeness completeness,
        String dedupMode,
        Duration preferWait) {

    /**
     * §11.4 completion strategy. {@link #ALL} — all-or-nothing — is the default
     * and needs no header; {@link #PARTIAL} is the opt-in best-effort mode a
     * client selects with {@code openEHR-federation-completeness: partial}.
     */
    public enum Completeness { ALL, PARTIAL }

    public static final String REQUEST_ATTRIBUTE = FederationRequestContext.class.getName();

    public static FederationRequestContext empty() {
        return new FederationRequestContext(new LinkedHashSet<>(), new LinkedHashSet<>(),
                Completeness.ALL, FederationHeaders.DEDUP_NONE, null);
    }

    public boolean hasHeaderTargets() {
        return !headerEndpointIds.isEmpty() || !headerOrganisationIds.isEmpty();
    }
}
