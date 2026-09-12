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

    public enum Completeness { BEST_EFFORT, ALL }

    public static final String REQUEST_ATTRIBUTE = FederationRequestContext.class.getName();

    public static FederationRequestContext empty() {
        return new FederationRequestContext(new LinkedHashSet<>(), new LinkedHashSet<>(),
                Completeness.BEST_EFFORT, FederationHeaders.DEDUP_NONE, null);
    }

    public boolean hasHeaderTargets() {
        return !headerEndpointIds.isEmpty() || !headerOrganisationIds.isEmpty();
    }
}
