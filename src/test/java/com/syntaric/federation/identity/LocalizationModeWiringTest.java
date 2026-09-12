// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity;

import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.identity.impl.AllNodesLocalizationService;
import com.syntaric.federation.identity.impl.mitz.MitzLocalizationService;
import com.syntaric.federation.identity.impl.nvi.NviLocalizationService;
import com.syntaric.federation.identity.spi.PatientLocalizationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code Localization.Mode} against the {@code havingValue} strings the
 * {@code @ConditionalOnProperty} annotations actually match on.
 *
 * <p>Spring compares the property <i>text</i>; the enum is not consulted at
 * all. So the enum can be renamed, or a constant added, and every conditional
 * still compiles while quietly selecting nothing — which in this codebase means
 * <i>no</i> {@link PatientLocalizationService} bean and a context that fails at
 * startup, or worse, two of them. The existing {@code Identity.Mode} enum is
 * decorative for exactly this reason; this test stops the new one from going
 * the same way.
 */
class LocalizationModeWiringTest {

    private static final Map<FederationProperties.Localization.Mode, Class<?>> BEANS_BY_MODE = Map.of(
            FederationProperties.Localization.Mode.NONE, AllNodesLocalizationService.class,
            FederationProperties.Localization.Mode.NVI, NviLocalizationService.class,
            FederationProperties.Localization.Mode.MITZ, MitzLocalizationService.class);

    @Test
    void everyModeHasExactlyOneImplementationAnnotatedForIt() {
        assertThat(BEANS_BY_MODE)
                .as("every Localization.Mode constant must select an implementation")
                .containsOnlyKeys(FederationProperties.Localization.Mode.values());

        BEANS_BY_MODE.forEach((mode, beanType) -> {
            ConditionalOnProperty conditional = beanType.getAnnotation(ConditionalOnProperty.class);
            assertThat(conditional)
                    .as("%s must be conditional — an unconditional second bean collides", beanType.getSimpleName())
                    .isNotNull();
            assertThat(conditional.name()).containsExactly("federation.localization.mode");
            assertThat(conditional.havingValue())
                    .as("%s must activate on mode=%s", beanType.getSimpleName(), mode.propertyValue())
                    .isEqualTo(mode.propertyValue());
        });
    }

    /**
     * The ask-all bean must match when the property is absent entirely, or a
     * deployment that has never heard of {@code federation.localization} loses its
     * only localization bean and fails to start.
     */
    @Test
    void askAllIsTheDefaultWhenThePropertyIsAbsent() {
        ConditionalOnProperty conditional =
                AllNodesLocalizationService.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional.matchIfMissing()).isTrue();
        assertThat(BEANS_BY_MODE.entrySet().stream()
                .filter(e -> e.getValue().getAnnotation(ConditionalOnProperty.class).matchIfMissing())
                .map(Map.Entry::getKey))
                .as("exactly one mode may be the implicit default")
                .containsExactly(FederationProperties.Localization.Mode.NONE);
    }

    @Test
    void propertyValueIsTheLowercasedConstant() {
        assertThat(FederationProperties.Localization.Mode.NONE.propertyValue()).isEqualTo("none");
        assertThat(FederationProperties.Localization.Mode.NVI.propertyValue()).isEqualTo("nvi");
        assertThat(FederationProperties.Localization.Mode.MITZ.propertyValue()).isEqualTo("mitz");
    }
}
