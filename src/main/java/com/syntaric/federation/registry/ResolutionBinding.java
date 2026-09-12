// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("resolution_binding")
public record ResolutionBinding(
        @Id Long id,
        String patientRefHash,
        String nodeId,
        String localEhrId,
        String consentStatus,
        Instant resolvedAt,
        Instant expiresAt) {

    public static final String CONSENT_PERMITTED = "permitted";
    public static final String CONSENT_DENIED = "denied";

    /**
     * No consent decision was available — distinct from {@code permitted}.
     *
     * <p>The column has always allowed {@code unknown}; the constant was simply
     * missing, so every write claimed {@code permitted}. That is the wrong
     * default to persist once a localization backend exists: "nobody asked" and
     * "the answer was yes" must not be cached as the same fact.
     */
    public static final String CONSENT_UNKNOWN = "unknown";

    public boolean isExpired(final Instant now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
