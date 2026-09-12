// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a localization (record-locator) service answered, and — crucially —
 * whether it answered at all.
 *
 * <p>A bare {@code List<EndpointDescriptor>} cannot distinguish <i>"this
 * patient has no records anywhere"</i> from <i>"the national index is
 * down"</i>: both arrive as an empty list. Collapsing them is the fail-open-to-
 * silence flaw the PIXm client already has — a query returns nothing and looks
 * like a legitimate empty result. {@link Outcome} keeps the two apart so the
 * pipeline can apply {@code federation.localization.on-failure} to one and report the
 * other as an honest negative.
 *
 * <p><b>A localization result names candidates, never nodes cleared to
 * answer</b> (spec §14, §14.4). Presence in {@link #endpointIds} means "ask
 * this node"; it does <b>not</b> mean consent was granted — the node still
 * applies N27 and may refuse. {@link #consentDeniedEndpointIds} is the only
 * part of this record that carries a consent decision, and only a
 * consent-aware localizer populates it.
 *
 * @param outcome              what happened
 * @param endpointIds          endpoints that may be queried; meaningful only for {@link Outcome#LOCALIZED}
 * @param consentDeniedEndpointIds endpoints a <i>consent-aware</i> localizer explicitly
 *                             refused (N27a). Empty for a plain record-locator, which is
 *                             the common case and carries no consent signal at all.
 * @param categoriesByEndpoint record categories per endpoint, in whatever coding the
 *                             regional locator uses, retained for telemetry and a future
 *                             category-scoped query even though nothing filters on them yet
 * @param error                operator-facing reason, set for {@link Outcome#UNAVAILABLE}
 */
public record LocalizationResult(
        Outcome outcome,
        Set<String> endpointIds,
        Set<String> consentDeniedEndpointIds,
        Map<String, Set<String>> categoriesByEndpoint,
        String error) {

    public enum Outcome {
        /** The service answered with a node set; query exactly those. */
        LOCALIZED,
        /** The service answered, and holds nothing for this patient. */
        NO_RECORDS,
        /** The service could not be reached or returned garbage; apply {@code on-failure}. */
        UNAVAILABLE,
        /** No localization configured — the spec's ask-all fallback. */
        DISABLED
    }

    public LocalizationResult {
        endpointIds = endpointIds == null ? Set.of() : Set.copyOf(endpointIds);
        consentDeniedEndpointIds = consentDeniedEndpointIds == null
                ? Set.of() : Set.copyOf(consentDeniedEndpointIds);
        categoriesByEndpoint = categoriesByEndpoint == null ? Map.of() : Map.copyOf(categoriesByEndpoint);
    }

    public static LocalizationResult disabled() {
        return new LocalizationResult(Outcome.DISABLED, Set.of(), Set.of(), Map.of(), null);
    }

    public static LocalizationResult noRecords() {
        return new LocalizationResult(Outcome.NO_RECORDS, Set.of(), Set.of(), Map.of(), null);
    }

    public static LocalizationResult unavailable(final String error) {
        return new LocalizationResult(Outcome.UNAVAILABLE, Set.of(), Set.of(), Map.of(), error);
    }

    /**
     * Localized to a candidate node set; an empty set is normalised to
     * {@link Outcome#NO_RECORDS}.
     *
     * <p>For a plain record-locator — XCPD ITI-55 and the common case — which
     * reports where data is and says nothing about release (§14.2).
     */
    public static LocalizationResult localized(final Set<String> endpointIds,
                                               final Map<String, Set<String>> categoriesByEndpoint) {
        return localized(endpointIds, Set.of(), categoriesByEndpoint);
    }

    /**
     * Localized by a <i>consent-aware</i> localizer (§14.4): candidates, plus
     * the nodes it explicitly refused on consent grounds.
     *
     * <p>The two sets mean different things and must not be merged. A node in
     * {@code consentDeniedEndpointIds} was refused and is reported
     * {@code consent-denied} under N27a. A node merely absent from
     * {@code endpointIds} holds no records — a where-answer, not a
     * consent-answer.
     */
    public static LocalizationResult localized(final Set<String> endpointIds,
                                               final Set<String> consentDeniedEndpointIds,
                                               final Map<String, Set<String>> categoriesByEndpoint) {
        if (endpointIds == null || endpointIds.isEmpty()) {
            return (consentDeniedEndpointIds == null || consentDeniedEndpointIds.isEmpty())
                    ? noRecords()
                    : new LocalizationResult(Outcome.NO_RECORDS, Set.of(),
                            consentDeniedEndpointIds, Map.of(), null);
        }
        return new LocalizationResult(Outcome.LOCALIZED, endpointIds,
                consentDeniedEndpointIds, categoriesByEndpoint, null);
    }

    /** True when the caller must consult only {@link #endpointIds}. */
    public boolean restrictsCandidates() {
        return outcome == Outcome.LOCALIZED || outcome == Outcome.NO_RECORDS;
    }

    /**
     * Whether this node is a candidate to <b>ask</b>.
     *
     * <p>Deliberately not named anything suggesting permission: §14.4 forbids
     * inferring consent from presence. The node still applies N27.
     */
    public boolean includes(final String endpointId) {
        return endpointIds.contains(endpointId);
    }

    /**
     * Whether a consent-aware localizer explicitly refused this node (N27a).
     *
     * <p>Distinct from {@code !includes(...)}: that means "holds no records
     * here", which is not a consent decision and must not be reported as one.
     */
    public boolean consentDenied(final String endpointId) {
        return consentDeniedEndpointIds.contains(endpointId);
    }

    public Set<String> categoriesFor(final String endpointId) {
        return categoriesByEndpoint.getOrDefault(endpointId, Set.of());
    }

    /** Category codes across every localized endpoint, for audit detail. */
    public List<String> allCategories() {
        return categoriesByEndpoint.values().stream()
                .flatMap(Set::stream)
                .distinct()
                .sorted()
                .toList();
    }
}
