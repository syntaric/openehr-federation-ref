// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.spi;

import com.syntaric.federation.query.EndpointDescriptor;

import java.util.List;

/**
 * N4 / CP-5: yields the candidate nodes for an undirected query (record
 * locator / XCPD role).
 *
 * <p>Exactly one implementation is active per deployment, selected by
 * {@code federation.localization.mode}: the ask-all fallback, or a regional Annex B
 * adapter. They are alternatives rather than a chain — a federation has one
 * localization authority, and consulting two would produce a union that neither
 * of them sanctioned.
 *
 * <p>Implementations must not throw: a backend failure is reported as
 * {@link LocalizationResult.Outcome#UNAVAILABLE} so the caller can apply the
 * configured {@code on-failure} policy, rather than as an exception that would
 * take down a query the operator may have chosen to let through.
 */
public interface PatientLocalizationService {

    LocalizationResult localize(final PatientToken token, final List<EndpointDescriptor> members);
}
