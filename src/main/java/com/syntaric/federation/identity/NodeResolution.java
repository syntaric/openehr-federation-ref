// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity;

/**
 * Per-endpoint outcome of patient identity resolution (feeds N6/N16 statuses).
 *
 * <p>{@code reason} is operator-facing prose explaining a non-{@code RESOLVED}
 * status — which localization decision dropped this node, say. It rides the
 * existing async telemetry path into {@code meta.endpoints[].error} rather than
 * costing a synchronous audit write per node (Invariant 0), and must never
 * carry a patient identifier.
 */
public record NodeResolution(String endpointId, String ehrId, Status status, String reason) {

    public NodeResolution(final String endpointId, final String ehrId, final Status status) {
        this(endpointId, ehrId, status, null);
    }

    public enum Status {
        RESOLVED,
        NOT_RESOLVED,

        /**
         * Localization did not name this node as a candidate.
         *
         * <p>Deliberately <b>not</b> {@code CONSENT_DENIED}. Spec §14.4: a
         * localization result is a set of candidates, and absence from it means
         * "holds no records here" — a where-answer, not a consent decision.
         * Reporting it as a consent denial would assert a decision no consent
         * authority made.
         */
        NOT_LOCALIZED,

        /**
         * A consent authority refused this node — either a consent-aware
         * localizer's Step-1 pre-filter (N27a) or a cached such decision.
         *
         * <p>Never inferred from mere absence from a candidate set.
         */
        CONSENT_DENIED
    }

    public boolean resolved() {
        return status == Status.RESOLVED;
    }
}
