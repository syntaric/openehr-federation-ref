// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.identity.spi.LocalizationResult;
import com.syntaric.federation.identity.spi.PatientLocalizationService;
import com.syntaric.federation.identity.spi.PatientToken;
import com.syntaric.federation.registry.ResolutionBindingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CP-5 and CP-19 at the spec level: what the gateway does with a
 * {@link LocalizationResult}, independent of where that result came from.
 *
 * <p>Localization (N4, §14) is an <b>abstract role</b> in the normative body of
 * the specification — something answers "which nodes hold records for this
 * patient". Concrete national services live in the informative Annex B, and §2.4
 * provides for a region supplying a different one. So the properties worth
 * pinning end-to-end are the gateway-side ones, and they are the same whichever
 * service produced the answer. This class drives a test-scoped localizer
 * directly rather than standing up a regional backend to elicit a result through
 * it.
 *
 * <p>The mode must still be set to something other than {@code none}: under
 * {@code none} the pipeline short-circuits to the ask-all fallback and never
 * consults a localizer at all — correctly, since that is what {@code none}
 * means. Setting it forks a Spring context, which is the cost of testing this
 * layer honestly; one forked context here replaces the two the regional ITs
 * needed.
 *
 * <p><b>CP-19 and consent.</b> N27 places consent enforcement on the node, and
 * N27a is explicit that a deployment with <b>no</b> consent service is fully
 * conformant — which is this build's position. What is testable at the gateway,
 * and is tested here, is that the gateway never usurps that obligation: it does
 * not infer consent from a localization hit, and it honours a localized node's
 * refusal instead of treating its own candidate set as authorisation (the §16
 * scenario 7(c) disagreement case).
 */
@Import(LocalizationPolicyIT.StubLocalizer.class)
class LocalizationPolicyIT extends IntegrationTestBase {

    /**
     * Any non-{@code none} mode will do — the stub below is {@code @Primary} and
     * answers whatever the mode selected. What matters is only that the pipeline
     * does not take the ask-all short-circuit.
     */
    @DynamicPropertySource
    static void localizationMode(DynamicPropertyRegistry registry) {
        // Additive only — the base class's own registration still runs, and
        // these two keys do not appear in it, so ordering between them does not
        // matter. (A class that needed to *override* a base value would have to
        // compose the registration explicitly instead, as InboundJwtSecurityIT
        // does, because ordering across a hierarchy is unspecified.)
        registry.add("federation.localization.mode", () -> "nvi");
        registry.add("federation.localization.on-failure", () -> "closed");
    }

    /** The AQL every test here issues: undirected, subject-scoped. */
    private static final String SUBJECT_QUERY =
            "SELECT c/uid/value AS composition_id, c/context/start_time/value AS start_time "
                    + "FROM EHR e CONTAINS COMPOSITION c "
                    + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'";

    /**
     * What the localizer will answer next. Set per test; the bean itself is
     * inert, which keeps the "what does the gateway do with X" question separate
     * from "how is X produced".
     */
    static final AtomicReference<LocalizationResult> NEXT_RESULT = new AtomicReference<>();

    @TestConfiguration
    static class StubLocalizer {

        @Bean
        @Primary
        PatientLocalizationService stubLocalizationService() {
            return new PatientLocalizationService() {
                @Override
                public LocalizationResult localize(PatientToken token, List<EndpointDescriptor> members) {
                    LocalizationResult result = NEXT_RESULT.get();
                    if (result == null) {
                        throw new IllegalStateException(
                                "a test drove a query without setting NEXT_RESULT");
                    }
                    return result;
                }
            };
        }
    }

    @Autowired
    private ResolutionBindingRepository bindings;

    /**
     * Clears cached localization decisions before <b>and after</b> every test.
     *
     * <p>Not incidental tidying. A denial is deliberately written to
     * {@code resolution_binding} with a 15-minute {@code cache-ttl} so the next
     * query inside the window skips the localization call entirely — the feature
     * working as designed. But the whole IT suite shares one Postgres, so a
     * denial left behind here keeps excluding that node from every later test,
     * including ones that never heard of localization. That bug has already bitten
     * once: a manual-verification IT silently stopped seeing {@code node_2}
     * because a binding from these tests was still denying it.
     *
     * <p>The {@code @AfterEach} half is the one that matters for other classes;
     * the {@code @BeforeEach} half keeps these tests independent of each other.
     */
    @BeforeEach
    void clearCachedLocalizationDecisionsBefore() {
        bindings.deleteAll();
        NEXT_RESULT.set(null);
    }

    @AfterEach
    void clearCachedLocalizationDecisionsAfter() {
        bindings.deleteAll();
    }

    // ---- CP-5: the candidate set decides who is contacted --------------------

    /**
     * §11.1: a member localization did not name is {@code not-localized}, not
     * {@code excluded} — nothing ruled it out, nothing ruled it in.
     *
     * <p>And because such a node was never <i>in scope</i>, it does not clear
     * {@code meta.complete} (§11.1 "What in scope means", N37). This is the
     * ordinary undirected query: a localizer naming one of three members must
     * still report a complete answer, or a healthy federation reports
     * {@code complete: false} on essentially every query and the flag stops
     * meaning anything.
     */
    @Test
    @Tag("CP-5")
    @Tag("CP-36")
    @DisplayName("only localized nodes are queried")
    void onlyLocalizedNodesAreQueried() {
        localizes(Set.of("node_1"));
        stubAllNodesAnswering();

        ResponseEntity<JsonNode> response = query();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(statusByEndpoint(response.getBody()))
                .containsEntry("node_1", "active")
                .containsEntry("node_2", "not-localized")
                .containsEntry("node_3", "not-localized");

        assertThat(response.getBody().get("meta").get("complete").asBoolean())
                .as("nodes that were never in scope must not clear meta.complete (§11.1)")
                .isTrue();

        assertQueried(NODE_1);
        assertNotQueried(NODE_2);
        assertNotQueried(NODE_3);
        assertThat(response.getBody().get("rows")).hasSize(1);
    }

    /**
     * The actual privacy property: a node that is "skipped" but still contacted
     * has learned exactly what localization exists to keep from it.
     *
     * <p>And the status is {@code excluded}, not {@code consent-denied}. A plain
     * record-locator answers where data is and says nothing about release
     * (§14.4: "absence may mean 'no consent'; presence does not mean 'consent
     * granted'"). Reporting a where-answer as a consent decision would assert a
     * decision no consent authority made.
     */
    @Test
    @Tag("CP-5")
    @DisplayName("non-localized nodes are reported excluded and never contacted")
    void nonLocalizedNodesAreReportedExcludedAndNeverContacted() {
        localizes(Set.of("node_1", "node_3"));
        stubAllNodesAnswering();

        ResponseEntity<JsonNode> response = query();

        Map<String, String> statuses = statusByEndpoint(response.getBody());
        assertThat(statuses)
                .containsEntry("node_1", "active")
                .containsEntry("node_2", "not-localized")
                .containsEntry("node_3", "active");
        assertThat(statuses.get("node_2"))
                .as("a node the locator did not name has had no consent decision made about it")
                .isNotEqualTo("consent-denied");
        assertQueried(NODE_1);
        assertNotQueried(NODE_2);
        assertQueried(NODE_3);
    }

    /** An index that holds nothing yields no candidates — no fan-out at all. */
    @Test
    @Tag("CP-5")
    @DisplayName("an empty answer excludes every node")
    void anEmptyAnswerExcludesEveryNode() {
        NEXT_RESULT.set(LocalizationResult.noRecords());
        stubAllNodesAnswering();

        ResponseEntity<JsonNode> response = query();

        assertThat(statusByEndpoint(response.getBody()).values()).containsOnly("not-localized");
        assertNotQueried(NODE_1);
        assertNotQueried(NODE_2);
        assertNotQueried(NODE_3);
        assertThat(response.getBody().get("rows")).isEmpty();
    }

    // ---- CP-19: localization filters in front of the gate, it is not the gate -

    /**
     * The disagreement case (§16 scenario 7(c), §14.4): localization names a
     * node, and the node refuses on consent anyway.
     *
     * <p>This is the whole point of the separation. The localizer said "records
     * are here"; the node is the consent authority and says "you may not have
     * them". The gateway must not treat its own localization hit as
     * authorisation, must not fail the query, and must report the refusal.
     *
     * <p>A gateway that inferred consent from localization would have no way to
     * express this — which is precisely why N27 puts the obligation on the node.
     */
    @Test
    @Tag("CP-19")
    @DisplayName("a localized node may still refuse on consent")
    void aLocalizedNodeMayStillRefuseOnConsent() {
        localizes(Set.of("node_1", "node_3"));
        stubAllNodesAnswering();
        // node_3 is localized — the locator says it holds records — but refuses.
        NODE_3.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/query/aql"))
                .willReturn(WireMock.aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"consent denied for this requester\"}")));

        ResponseEntity<JsonNode> response = query();

        // The query succeeds and returns what node_1 had: one node refusing is
        // a partial result, never a failed federation.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("rows")).hasSize(1);

        Map<String, String> statuses = statusByEndpoint(response.getBody());
        assertThat(statuses).containsEntry("node_1", "active");
        assertThat(statuses.get("node_3"))
                .as("a localized node that refused must be reported, not silently dropped")
                .isNotEqualTo("active");
        // node_2 was never localized: still a where-answer, now `not-localized`.
        assertThat(statuses).containsEntry("node_2", "not-localized");
        // And the node WAS asked — localization is a filter in front of the
        // gate, not the gate (§13.2.1).
        assertQueried(NODE_3);
    }

    /**
     * A <i>consent-aware</i> localizer's explicit refusal is the one thing that
     * may be reported as a consent denial (N27a) — and it is reported
     * differently from a node that simply holds nothing.
     */
    @Test
    @Tag("CP-19")
    @DisplayName("a consent-aware localizer's refusal is reported as consent-denied")
    void aConsentAwareLocalizerReportsConsentDenied() {
        NEXT_RESULT.set(LocalizationResult.localized(
                Set.of("node_1"), Set.of("node_3"), Map.of()));
        stubAllNodesAnswering();

        ResponseEntity<JsonNode> response = query();

        Map<String, String> statuses = statusByEndpoint(response.getBody());
        assertThat(statuses).containsEntry("node_1", "active");
        assertThat(statuses.get("node_3"))
                .as("an explicit consent refusal is the one case N27a lets the gateway report as such")
                .isEqualTo("consent-denied");
        assertThat(statuses.get("node_2"))
                .as("absent from both sets: a where-answer, never a consent decision")
                .isEqualTo("not-localized");
        assertNotQueried(NODE_2);
        assertNotQueried(NODE_3);
    }

    // ---- failure and opt-out policy -----------------------------------------

    /**
     * {@code on-failure=closed} — the default — means an unreachable localizer
     * yields no candidates. The alternative, quietly asking every node, discards
     * precisely the privacy property localization exists to provide, so it must
     * never be what a failure degrades to.
     */
    @Test
    @Tag("CP-5")
    @DisplayName("an unavailable localizer fails closed by default")
    void anUnavailableLocalizerFailsClosedByDefault() {
        NEXT_RESULT.set(LocalizationResult.unavailable("national index unreachable"));
        stubAllNodesAnswering();

        ResponseEntity<JsonNode> response = query();

        assertThat(statusByEndpoint(response.getBody()).values()).containsOnly("not-localized");
        assertNotQueried(NODE_1);
        assertNotQueried(NODE_2);
        assertNotQueried(NODE_3);
    }

    /**
     * With no localization configured, the spec's ask-all fallback (§4.3 variant
     * B) is taken verbatim: every member is queried and nothing is reported as
     * excluded, because nothing was excluded by anything.
     */
    @Test
    @Tag("CP-5")
    @DisplayName("ask-all is taken verbatim when localization is disabled")
    void askAllIsTakenVerbatimWhenLocalizationIsDisabled() {
        NEXT_RESULT.set(LocalizationResult.disabled());
        stubAllNodesAnswering();

        ResponseEntity<JsonNode> response = query();

        assertThat(statusByEndpoint(response.getBody()).values())
                .as("every node was a candidate, so none may be reported as filtered out")
                .doesNotContain("not-localized", "consent-denied");
        assertQueried(NODE_1);
        assertQueried(NODE_2);
        assertQueried(NODE_3);
        assertThat(response.getBody().get("rows")).hasSize(3);
    }

    // ---- helpers -------------------------------------------------------------

    private static void localizes(Set<String> endpointIds) {
        NEXT_RESULT.set(LocalizationResult.localized(endpointIds, Map.of()));
    }

    private ResponseEntity<JsonNode> query() {
        return exchangeJson(HttpMethod.POST, "/v1/query/aql", Map.of("q", SUBJECT_QUERY), Map.of());
    }

    /** Stubs all three member CDRs so any that IS queried answers successfully. */
    private static void stubAllNodesAnswering() {
        stubQuery(NODE_1, "[" + row("aaaaaaaa-1111-0000-0000-000000000001::cdr1.test::1",
                "2026-01-01T10:00") + "]", 0);
        stubQuery(NODE_2, "[" + row("bbbbbbbb-2222-0000-0000-000000000001::cdr2.test::1",
                "2026-01-01T11:00") + "]", 0);
        stubQuery(NODE_3, "[" + row("cccccccc-3333-0000-0000-000000000001::cdr3.test::1",
                "2026-01-01T09:00") + "]", 0);
    }

    /** Asserts a node received no AQL at all — the privacy property under test. */
    private static void assertNotQueried(com.github.tomakehurst.wiremock.WireMockServer node) {
        node.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/query/aql")));
    }

    private static void assertQueried(com.github.tomakehurst.wiremock.WireMockServer node) {
        node.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/query/aql")));
    }

    private static Map<String, String> statusByEndpoint(JsonNode body) {
        Map<String, String> statuses = new LinkedHashMap<>();
        body.get("meta").get("endpoints").forEach(endpoint ->
                statuses.put(endpoint.get("id").asText(), endpoint.get("status").asText()));
        return statuses;
    }
}
