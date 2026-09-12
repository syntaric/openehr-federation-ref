// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.config;

import java.time.Duration;

/**
 * {@code federation.localization} values for unit tests that build
 * {@link FederationProperties} by hand.
 *
 * <p>The record is bound from YAML in production, where {@code @DefaultValue}
 * fills everything in; a hand-built instance has to spell out every component,
 * which is a lot of noise in a test about something else entirely. This keeps
 * that noise in one place — and means adding a field to the config tree is one
 * edit rather than one per test.
 */
public final class LocalizationProperties {

    private LocalizationProperties() {
    }

    /** The production default: ask-all, no localizer configured. */
    public static FederationProperties.Localization disabled() {
        return of(FederationProperties.Localization.Mode.NONE,
                FederationProperties.Localization.OnFailure.CLOSED,
                Duration.ofSeconds(3));
    }

    public static FederationProperties.Localization of(FederationProperties.Localization.Mode mode,
                                                 FederationProperties.Localization.OnFailure onFailure,
                                                 Duration timeout) {
        return new FederationProperties.Localization(
                mode, onFailure, timeout, Duration.ofMinutes(15));
    }
}
