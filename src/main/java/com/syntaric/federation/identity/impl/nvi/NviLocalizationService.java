// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.impl.nvi;

import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.identity.spi.LocalizationResult;
import com.syntaric.federation.identity.spi.PatientLocalizationService;
import com.syntaric.federation.identity.spi.PatientToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <b>Unimplemented reference stub for a regional record-locator binding.</b>
 *
 * <p>This class is a worked example of the extension point described in spec
 * §2.4, not a working adapter. It ships deliberately empty, and the reasons are
 * worth reading before replacing it.
 *
 * <h2>Why this is a stub</h2>
 *
 * <p>The normative body of the specification (§1–§18) defines localization as an
 * <i>abstract role</i>: something answers "which nodes hold records for this
 * patient", and the gateway queries those. It names IHE profiles (XCPD ITI-55,
 * PIXm) only as <i>proposed bindings</i>. Every concrete national service — the
 * Dutch NVI among them — appears only in the <b>informative</b> Annex B. §2.4 is
 * explicit that a different region may supply a different Annex B without
 * touching §1–§18.
 *
 * <p>So a reference implementation that shipped one country's adapter would be
 * presenting a regional choice as though it were part of the specification. What
 * belongs here instead is the seam: {@link PatientLocalizationService}, the
 * wiring that selects an implementation, and this guide.
 *
 * <h2>Implementing this adapter</h2>
 *
 * <ol>
 *   <li><b>Resolve the patient identifier your service expects.</b> The incoming
 *       {@link PatientToken} carries whatever the federation's identity mode
 *       produced. A national index usually keys on a national person identifier,
 *       which may need pseudonymisation before it leaves this process.</li>
 *   <li><b>Call the regional service</b> with the deployment's own credentials,
 *       inside the budget at {@code federation.localization.timeout}. That budget is
 *       separate from the fan-out budget on purpose: this call happens inside the
 *       clinical request, and a slow index must not silently consume the
 *       allowance member nodes are relying on.</li>
 *   <li><b>Map its answer back to endpoint ids</b> — see "Identifier mapping"
 *       below.</li>
 *   <li><b>Return the right factory</b> — see "Which factory" below.</li>
 *   <li><b>Never throw.</b> {@link PatientLocalizationService} requires that a
 *       backend failure is reported as
 *       {@link LocalizationResult.Outcome#UNAVAILABLE} so the pipeline can apply
 *       the configured {@code on-failure} policy. An exception thrown from here
 *       takes down a query the operator may have chosen to let through.</li>
 * </ol>
 *
 * <h2>Which factory: the distinction that matters most</h2>
 *
 * <p>{@link LocalizationResult} offers two {@code localized(...)} factories and
 * choosing the wrong one is a clinical-safety bug, not a style question.
 *
 * <ul>
 *   <li>{@link LocalizationResult#localized(java.util.Set, java.util.Map)} — for
 *       a <b>plain record-locator</b> (the XCPD ITI-55 shape, and the common
 *       case). It answers <i>where records are</i> and says nothing whatsoever
 *       about release. This is almost certainly the one you want.</li>
 *   <li>{@link LocalizationResult#localized(java.util.Set, java.util.Set,
 *       java.util.Map)} — only for a <b>consent-aware</b> service that genuinely
 *       adjudicates release.</li>
 * </ul>
 *
 * <p>The second parameter is not a general "excluded nodes" list. A node absent
 * from {@code endpointIds} <i>holds no records</i>; a node in
 * {@code consentDeniedEndpointIds} <i>was refused</i>. The gateway reports the
 * second as {@code consent-denied} under N27a. Passing a merely-absent node there
 * asserts a consent decision that no consent authority ever made (§14.4) — it
 * puts a fabricated refusal into the audit trail.
 *
 * <p>Note also that presence in {@code endpointIds} is a candidacy signal, never
 * a clearance: the node still applies N27 and may refuse on its own.
 *
 * <h2>Identifier mapping</h2>
 *
 * <p>Regional services identify organisations in their own namespace (a URA
 * number, an OID, a care-provider id) — not by this gateway's endpoint ids. Map
 * between them with {@code NodeIdentifier} registry rows, which exist for exactly
 * this purpose. Do not hard-code the correspondence: it is deployment data that
 * changes when a member joins or leaves, and a code change per membership change
 * is how a federation's registry drifts out of date.
 *
 * <h2>Why UNAVAILABLE and not DISABLED</h2>
 *
 * <p>This stub returns {@link LocalizationResult.Outcome#UNAVAILABLE}, so under
 * the default {@code on-failure: closed} the query is audited as
 * {@code LOCALIZATION_FAILED} and returns no records.
 *
 * <p>{@code DISABLED} would be wrong, and dangerously so. {@code DISABLED} means
 * "no localization is configured", and the pipeline correctly answers that by
 * falling back to ask-all — querying every member. But an operator who set
 * {@code federation.localization.mode=nvi} asked for the opposite: they asked for
 * queries to be <i>narrowed</i>, so that nodes holding nothing for this patient
 * never learn the patient was asked about. Silently degrading an unimplemented
 * adapter to ask-all would discard precisely the privacy property the adapter
 * exists to provide — and it would do it quietly, at the moment the operator
 * believed they had just switched that property on.
 *
 * <p>Failing closed is the honest answer: this deployment is configured for a
 * localizer that cannot answer.
 *
 * @see com.syntaric.federation.identity.impl.AllNodesLocalizationService the conformant
 *      ask-all fallback (spec §4.3 variant B)
 */
@Component
@ConditionalOnProperty(name = "federation.localization.mode", havingValue = "nvi")
public class NviLocalizationService implements PatientLocalizationService {

    static final String NOT_IMPLEMENTED =
            "Localization mode 'nvi' is configured, but this build ships only a "
                    + "reference stub for it. See NviLocalizationService for an "
                    + "implementation guide, or set federation.localization.mode=none to "
                    + "use the conformant ask-all fallback.";

    @Override
    public LocalizationResult localize(final PatientToken token, final List<EndpointDescriptor> members) {
        return LocalizationResult.unavailable(NOT_IMPLEMENTED);
    }
}
