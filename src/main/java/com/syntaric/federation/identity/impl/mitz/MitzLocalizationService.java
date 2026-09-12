// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.impl.mitz;

import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.identity.spi.LocalizationResult;
import com.syntaric.federation.identity.spi.PatientLocalizationService;
import com.syntaric.federation.identity.spi.PatientToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <b>Unimplemented reference stub for a regional <i>consent-aware</i>
 * localization binding.</b>
 *
 * <p>Its sibling {@link com.syntaric.federation.identity.impl.nvi.NviLocalizationService}
 * stubs a plain record-locator — a service that answers <i>where</i> records
 * are. This one stubs the other shape: a service that also adjudicates
 * <i>release</i>. The Dutch Mitz is the example the specification uses, and like
 * NVI it appears only in the informative Annex B; §2.4 provides for a region
 * supplying a different Annex B without touching the normative §1–§18.
 *
 * <p>Read {@code NviLocalizationService} first — the implementation steps, the
 * identifier-mapping rule, the must-not-throw contract and the reasoning behind
 * returning {@code UNAVAILABLE} rather than {@code DISABLED} all apply here
 * unchanged. What follows is only what differs for a consent-aware service.
 *
 * <h2>Consent is a separate axis from location</h2>
 *
 * <p>A consent-aware localizer is the one case where
 * {@link LocalizationResult#localized(java.util.Set, java.util.Set,
 * java.util.Map)} — the three-argument factory — is the correct choice. It takes
 * both sets because they answer different questions:
 *
 * <ul>
 *   <li><b>{@code endpointIds}</b> — nodes to ask. A <i>where</i>-answer.</li>
 *   <li><b>{@code consentDeniedEndpointIds}</b> — nodes this authority
 *       explicitly <i>refused</i> on consent grounds. A <i>release</i>-answer,
 *       reported to the caller as {@code consent-denied} under N27a.</li>
 * </ul>
 *
 * <p>Keep them apart even when it seems pedantic. A node that simply holds no
 * records for this patient belongs in neither set — dropping it into
 * {@code consentDeniedEndpointIds} records a refusal that never happened, and
 * §14.4 forbids exactly that conflation. The inverse error is just as bad:
 * putting a genuinely refused node in neither set loses the refusal, and the
 * caller cannot distinguish "nobody there" from "you were told no".
 *
 * <h2>Localization filters; it does not gate</h2>
 *
 * <p>A consent authority's answer narrows the candidate set. It does not
 * <i>replace</i> the node's own decision. A node that this service localized and
 * did not refuse may still answer 403 when queried, and that is correct
 * behaviour, not a contradiction — under N27 each node applies its own consent
 * policy at the point of access (§13.2.1). Implementations must not treat
 * inclusion here as authorisation there, and must not suppress a node's refusal
 * because the localizer had cleared it.
 *
 * <h2>Staleness is a correctness bound</h2>
 *
 * <p>{@code federation.localization.cache-ttl} is short by default and that is not a
 * performance trade-off. Consent can be revoked at any moment, so the TTL is the
 * window during which this gateway may act on a permission that no longer holds.
 * Treat lengthening it as a clinical-governance decision.
 *
 * <p><b>A deployment with no consent service at all is fully conformant</b>
 * (N27a). Leaving {@code federation.localization.mode} at {@code none} is a supported
 * configuration, not a gap to be filled — under it, each node remains
 * responsible for its own consent enforcement, which is where the specification
 * places that duty regardless.
 *
 * @see com.syntaric.federation.identity.impl.nvi.NviLocalizationService the plain
 *      record-locator stub, and the main implementation guide
 */
@Component
@ConditionalOnProperty(name = "federation.localization.mode", havingValue = "mitz")
public class MitzLocalizationService implements PatientLocalizationService {

    static final String NOT_IMPLEMENTED =
            "Localization mode 'mitz' is configured, but this build ships only a "
                    + "reference stub for it. See MitzLocalizationService for an "
                    + "implementation guide, or set federation.localization.mode=none to "
                    + "use the conformant ask-all fallback (N27a: a deployment with "
                    + "no consent service is fully conformant).";

    @Override
    public LocalizationResult localize(final PatientToken token, final List<EndpointDescriptor> members) {
        return LocalizationResult.unavailable(NOT_IMPLEMENTED);
    }
}
