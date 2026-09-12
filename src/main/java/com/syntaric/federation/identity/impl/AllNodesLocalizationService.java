// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.impl;

import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.identity.spi.LocalizationResult;
import com.syntaric.federation.identity.spi.PatientLocalizationService;
import com.syntaric.federation.identity.spi.PatientToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ask-all-PIX default (N4 fallback, spec §4.3 variant B): every member is a
 * candidate and the cross-reference step decides who actually holds the
 * patient. Conformant, but it means every node in the federation learns that
 * someone asked about this patient — which is precisely what a regional record
 * locator exists to prevent.
 *
 * <p>The conditional is <b>load-bearing, not decorative</b>. Without it this
 * is an unconditional bean and adding any second
 * {@link PatientLocalizationService} fails the context at startup with
 * {@code NoUniqueBeanDefinitionException}. {@code matchIfMissing = true} keeps
 * a deployment that has never heard of {@code federation.localization} working
 * unchanged.
 */
@Component
@ConditionalOnProperty(name = "federation.localization.mode", havingValue = "none", matchIfMissing = true)
public class AllNodesLocalizationService implements PatientLocalizationService {

    /**
     * Returns {@link LocalizationResult.Outcome#DISABLED} rather than "all
     * members localized". The distinction matters downstream: a node skipped
     * under a real localizer is {@code consent-denied} (localization denied
     * inclusion), whereas under ask-all nothing was denied anything and the
     * pipeline must take its original path verbatim.
     */
    @Override
    public LocalizationResult localize(final PatientToken token, final List<EndpointDescriptor> members) {
        return LocalizationResult.disabled();
    }
}
