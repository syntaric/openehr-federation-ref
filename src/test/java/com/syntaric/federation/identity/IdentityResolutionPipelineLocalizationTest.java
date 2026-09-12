// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity;
import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.identity.spi.LocalizationResult;
import com.syntaric.federation.identity.spi.PatientCrossReferenceService;
import com.syntaric.federation.identity.spi.PatientLocalizationService;
import com.syntaric.federation.identity.spi.PatientToken;
import com.syntaric.federation.registry.ResolutionBinding;
import com.syntaric.federation.registry.ResolutionBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the pipeline turns a {@link LocalizationResult} into per-node statuses —
 * the behaviour CP-5 and CP-19 actually rest on.
 */
class IdentityResolutionPipelineLocalizationTest {

    private static final PatientToken TOKEN = new PatientToken("9999031234", "facade");
    private static final List<EndpointDescriptor> TARGETS = List.of(
            endpoint("ep_1", "node_1"), endpoint("ep_2", "node_2"), endpoint("ep_3", "node_3"));

    private final PatientCrossReferenceService crossReference = mock(PatientCrossReferenceService.class);
    private final ResolutionBindingRepository bindings = mock(ResolutionBindingRepository.class);
    private final List<ResolutionBinding> saved = new ArrayList<>();

    @BeforeEach
    void stubCollaborators() {
        when(bindings.findByPatientRefHashAndNodeId(anyString(), anyString())).thenReturn(Optional.empty());
        when(bindings.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(crossReference.resolveEhrId(any(), any()))
                .thenAnswer(invocation -> Optional.of("ehr-for-"
                        + ((EndpointDescriptor) invocation.getArgument(1)).endpointId()));
    }

    // ---- DISABLED: today's behaviour, byte-identical ------------------------

    @Test
    void disabledQueriesEveryMemberAndAsksNoLocalizer() {
        PatientLocalizationService localizer = mock(PatientLocalizationService.class);

        List<NodeResolution> results = pipeline(mode("none"), localizer).resolve(TOKEN, TARGETS);

        assertThat(results).extracting(NodeResolution::status)
                .containsOnly(NodeResolution.Status.RESOLVED);
        verify(localizer, never()).localize(any(), any());
    }

    /**
     * Resolving a patient at a node says where the data is, never that it may
     * be released (N27) — so the cached binding says {@code unknown} in every
     * mode, and the node decides.
     */
    @Test
    void resolutionNeverAssertsConsent() {
        pipeline(mode("none"), fixed(LocalizationResult.disabled())).resolve(TOKEN, TARGETS);

        assertThat(saved).extracting(ResolutionBinding::consentStatus)
                .containsOnly(ResolutionBinding.CONSENT_UNKNOWN);
    }

    // ---- LOCALIZED ----------------------------------------------------------

    @Test
    void localizedQueriesOnlyTheReturnedNodesAndExcludesTheRest() {
        LocalizationResult result = LocalizationResult.localized(
                Set.of("ep_1"), Map.of("ep_1", Set.of("GGC01")));

        List<NodeResolution> results = pipeline(mode("nvi"), fixed(result)).resolve(TOKEN, TARGETS);

        assertThat(results).extracting(NodeResolution::endpointId, NodeResolution::status)
                .containsExactly(
                        tuple("ep_1", NodeResolution.Status.RESOLVED),
                        tuple("ep_2", NodeResolution.Status.NOT_LOCALIZED),
                        tuple("ep_3", NodeResolution.Status.NOT_LOCALIZED));
        verify(crossReference, never()).resolveEhrId(any(), eq(TARGETS.get(1)));
    }

    /**
     * Spec §14.4: a localization result is a set of candidates. Absence means
     * "holds no records here" — a where-answer, not a consent decision — so it
     * must not be reported as {@code consent-denied}. Nor as
     * {@code not-resolved}, which §11.1 reserves for a cross-reference miss;
     * this node was never asked.
     */
    @Test
    void nodesAbsentFromTheCandidateSetAreNeitherConsentDeniedNorUnresolved() {
        List<NodeResolution> results = pipeline(mode("nvi"),
                fixed(LocalizationResult.localized(Set.of("ep_1"), Map.of()))).resolve(TOKEN, TARGETS);

        assertThat(results).filteredOn(r -> !r.endpointId().equals("ep_1"))
                .extracting(NodeResolution::status)
                .containsOnly(NodeResolution.Status.NOT_LOCALIZED)
                .doesNotContain(NodeResolution.Status.CONSENT_DENIED,
                        NodeResolution.Status.NOT_RESOLVED);
    }

    /**
     * The heart of the §14.4 separation: a node that localization simply did
     * not name must leave <b>no</b> consent record behind. Caching a
     * where-answer under {@code consent_status} is precisely the collapse the
     * spec forbids — and it would make a genuine revocation
     * indistinguishable from an empty index result.
     */
    @Test
    void absenceFromTheCandidateSetIsNotCachedAsAConsentDecision() {
        pipeline(mode("nvi"), fixed(LocalizationResult.localized(Set.of("ep_1"), Map.of())))
                .resolve(TOKEN, TARGETS);

        assertThat(saved).extracting(ResolutionBinding::nodeId)
                .as("only the candidate node is resolved and cached at all")
                .containsExactly("node_1");
        assertThat(saved).extracting(ResolutionBinding::consentStatus)
                .doesNotContain(ResolutionBinding.CONSENT_DENIED);
    }

    /**
     * §14.4: "presence does not mean consent granted — only that nothing
     * upstream ruled it out." A localization hit must never be written back as
     * {@code permitted}; the node still applies N27.
     */
    @Test
    void aLocalizationHitIsNeverCachedAsConsentPermitted() {
        pipeline(mode("nvi"), fixed(LocalizationResult.localized(Set.of("ep_1"), Map.of())))
                .resolve(TOKEN, TARGETS);

        assertThat(saved).extracting(ResolutionBinding::consentStatus)
                .containsOnly(ResolutionBinding.CONSENT_UNKNOWN)
                .doesNotContain(ResolutionBinding.CONSENT_PERMITTED);
    }

    // ---- N27a: the optional consent pre-filter ------------------------------

    /**
     * A consent-aware localizer's <i>explicit</i> refusal is the one thing that
     * may be reported and cached as a consent denial (N27a).
     */
    @Test
    void anExplicitConsentRefusalIsReportedAndCachedAsDenied() {
        LocalizationResult result = LocalizationResult.localized(
                Set.of("ep_1"), Set.of("ep_2"), Map.of());

        List<NodeResolution> results = pipeline(mode("nvi"), fixed(result)).resolve(TOKEN, TARGETS);

        assertThat(results).extracting(NodeResolution::endpointId, NodeResolution::status)
                .containsExactly(
                        tuple("ep_1", NodeResolution.Status.RESOLVED),
                        tuple("ep_2", NodeResolution.Status.CONSENT_DENIED),
                        // ep_3 was never mentioned either way: absence, not refusal.
                        tuple("ep_3", NodeResolution.Status.NOT_LOCALIZED));

        assertThat(saved).filteredOn(b -> b.nodeId().equals("node_2"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.consentStatus()).isEqualTo(ResolutionBinding.CONSENT_DENIED);
                    assertThat(binding.localEhrId())
                            .as("a node refused on consent must not keep a usable patient handle")
                            .isNull();
                });
    }

    // ---- NO_RECORDS ---------------------------------------------------------

    /** "Nothing found anywhere" is a where-answer about every node, not a mass consent denial. */
    @Test
    void noRecordsExcludesEveryNodeAndCallsNone() {
        List<NodeResolution> results = pipeline(mode("nvi"), fixed(LocalizationResult.noRecords()))
                .resolve(TOKEN, TARGETS);

        assertThat(results).extracting(NodeResolution::status)
                .containsOnly(NodeResolution.Status.NOT_LOCALIZED);
        assertThat(results).extracting(NodeResolution::reason)
                .containsOnly("localization found no records for this patient at any member node");
        assertThat(saved).as("a where-answer leaves no consent record").isEmpty();
        verify(crossReference, never()).resolveEhrId(any(), any());
    }

    // ---- UNAVAILABLE: the on-failure switch ---------------------------------

    @Test
    void unavailableFailsClosedByDefault() {
        FederationProperties properties = mode("nvi");
        assertThat(properties.localization().onFailure())
                .isEqualTo(FederationProperties.Localization.OnFailure.CLOSED);

        List<NodeResolution> results = pipeline(properties,
                fixed(LocalizationResult.unavailable("connection refused"))).resolve(TOKEN, TARGETS);

        // Nothing is queried — but an unreachable locator is an availability
        // failure, not a consent refusal, so no node is reported or cached as
        // consent-denied.
        assertThat(results).extracting(NodeResolution::status)
                .containsOnly(NodeResolution.Status.NOT_LOCALIZED);
        assertThat(saved).as("a backend that is merely down has denied nobody").isEmpty();
        verify(crossReference, never()).resolveEhrId(any(), any());
    }

    @Test
    void unavailableWithAskAllQueriesEveryMemberAndStillAudits() {
        FederationProperties properties = mode("nvi", "ask-all");

        List<NodeResolution> results = pipeline(properties,
                fixed(LocalizationResult.unavailable("connection refused"))).resolve(TOKEN, TARGETS);

        assertThat(results).extracting(NodeResolution::status)
                .containsOnly(NodeResolution.Status.RESOLVED);
    }

    /**
     * A backend that throws despite the SPI contract must still land on the
     * on-failure policy — never propagate and kill a query the operator may
     * have configured to let through.
     */
    @Test
    void aThrowingBackendIsTreatedAsUnavailable() {
        PatientLocalizationService broken = (token, members) -> {
            throw new IllegalStateException("boom");
        };

        List<NodeResolution> results = pipeline(mode("nvi", "ask-all"), broken).resolve(TOKEN, TARGETS);

        assertThat(results).extracting(NodeResolution::status)
                .containsOnly(NodeResolution.Status.RESOLVED);
    }

    /**
     * Invariant 0's reasoning applied to localization: a hung national service
     * must not spend the clinical fan-out budget. It fails as a localizer,
     * inside its own timeout.
     */
    @Test
    void aHangingBackendIsBoundedByTheLocalizationTimeout() {
        PatientLocalizationService hanging = (token, members) -> {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LocalizationResult.disabled();
        };
        FederationProperties properties = mode("nvi", "closed", Duration.ofMillis(120));

        long startedAt = System.nanoTime();
        List<NodeResolution> results = pipeline(properties, hanging).resolve(TOKEN, TARGETS);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        assertThat(results).extracting(NodeResolution::status)
                .containsOnly(NodeResolution.Status.NOT_LOCALIZED);
    }

    // ---- helpers ------------------------------------------------------------

    private IdentityResolutionPipeline pipeline(FederationProperties properties,
                                                PatientLocalizationService localizer) {
        return new IdentityResolutionPipeline(localizer, crossReference, bindings,
                hasher(properties), properties);
    }

    private static PatientRefHasher hasher(FederationProperties properties) {
        return new PatientRefHasher(properties);
    }

    private static PatientLocalizationService fixed(LocalizationResult result) {
        return (token, members) -> result;
    }

    private static FederationProperties mode(String mode) {
        return mode(mode, "closed");
    }

    private static FederationProperties mode(String mode, String onFailure) {
        return mode(mode, onFailure, Duration.ofSeconds(3));
    }

    private static FederationProperties mode(String mode, String onFailure, Duration timeout) {
        FederationProperties.Localization localization = new FederationProperties.Localization(
                FederationProperties.Localization.Mode.valueOf(mode.toUpperCase(java.util.Locale.ROOT)),
                FederationProperties.Localization.OnFailure.valueOf(
                        onFailure.toUpperCase(java.util.Locale.ROOT).replace('-', '_')),
                timeout, Duration.ofMinutes(15));
        return new FederationProperties(
                new FederationProperties.Federation("test-federation", "Test Federation", null,
                        "test-gateway", "0.1.0", null),
                new FederationProperties.Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(2)),
                new FederationProperties.Identity(FederationProperties.Identity.Mode.STATIC, Map.of(),
                        "test-secret", Duration.ofHours(24)),
                localization,
                new FederationProperties.Security("passthrough", Map.of(), null),
                null);
    }

    private static EndpointDescriptor endpoint(String endpointId, String nodeId) {
        return new EndpointDescriptor(endpointId, nodeId, nodeId + ".test", "org-a",
                "http://" + nodeId, null, "passthrough", "WireMock", "1.0");
    }
}
