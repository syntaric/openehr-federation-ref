// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.query.FederationRequestContext.Completeness;
import com.syntaric.federation.query.fanout.NodeOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §11.1's "which statuses fail the query" table, row by row, under both
 * completion strategies (§11.4, N37).
 *
 * <p>Two questions per outcome — does it clear {@code complete}, does it fail
 * the query — and the table's point is that they have different answers:
 * {@code not-resolved} clears the flag and never fails; {@code not-localized}
 * does neither; an unanswered node does both.
 */
@Tag("CP-30")
class CompletionPolicyTest {

    private static final NodeOutcome ACTIVE =
            NodeOutcome.success("n1", 10, List.of(), List.of(), "http://n1");
    private static final NodeOutcome OFFLINE =
            NodeOutcome.offline("n2", 5, "connection refused", "http://n2");
    private static final NodeOutcome TIMEOUT = NodeOutcome.timeout("n3", 1500, "http://n3");
    private static final NodeOutcome NODE_ERROR =
            NodeOutcome.nodeError("n4", 20, 500, "boom", "http://n4");
    private static final NodeOutcome NOT_RESOLVED =
            NodeOutcome.skipped("n5", NodeOutcome.STATUS_NOT_RESOLVED, "no ehr");
    private static final NodeOutcome CONSENT_DENIED =
            NodeOutcome.skipped("n6", NodeOutcome.STATUS_CONSENT_DENIED, "refused");
    private static final NodeOutcome EXCLUDED =
            NodeOutcome.skipped("n7", NodeOutcome.STATUS_EXCLUDED, "directive");
    private static final NodeOutcome NOT_LOCALIZED =
            NodeOutcome.skipped("n8", NodeOutcome.STATUS_NOT_LOCALIZED, "not named");

    // ---- statuses that never fail, in either mode ---------------------------

    @ParameterizedTest
    @EnumSource(Completeness.class)
    @DisplayName("active alone: complete, 200")
    void activeIsComplete(final Completeness mode) {
        assertThat(CompletionPolicy.decide(List.of(ACTIVE), mode))
                .isEqualTo(new CompletionPolicy.Succeed(true));
    }

    @ParameterizedTest
    @EnumSource(Completeness.class)
    @DisplayName("excluded and not-localized were never in scope: complete stays true")
    void outOfScopeStatusesDoNotClearTheFlag(final Completeness mode) {
        assertThat(CompletionPolicy.decide(List.of(ACTIVE, EXCLUDED, NOT_LOCALIZED), mode))
                .isEqualTo(new CompletionPolicy.Succeed(true));
    }

    @ParameterizedTest
    @EnumSource(Completeness.class)
    @DisplayName("not-resolved clears complete and never fails (§11.3 carve-out)")
    void notResolvedClearsButDoesNotFail(final Completeness mode) {
        assertThat(CompletionPolicy.decide(List.of(ACTIVE, NOT_RESOLVED), mode))
                .isEqualTo(new CompletionPolicy.Succeed(false));
    }

    @ParameterizedTest
    @EnumSource(Completeness.class)
    @DisplayName("consent-denied clears complete and never fails (§11.3)")
    void consentDeniedClearsButDoesNotFail(final Completeness mode) {
        assertThat(CompletionPolicy.decide(List.of(ACTIVE, CONSENT_DENIED), mode))
                .isEqualTo(new CompletionPolicy.Succeed(false));
    }

    @ParameterizedTest
    @EnumSource(Completeness.class)
    @DisplayName("patient found nowhere: every node not-resolved is a 200 with complete false")
    void foundNowhereIsNotAFailure(final Completeness mode) {
        assertThat(CompletionPolicy.decide(List.of(NOT_RESOLVED, NOT_RESOLVED), mode))
                .isEqualTo(new CompletionPolicy.Succeed(false));
    }

    @ParameterizedTest
    @EnumSource(Completeness.class)
    @DisplayName("an empty federation has nothing to be incomplete about")
    void emptyOutcomesAreComplete(final Completeness mode) {
        assertThat(CompletionPolicy.decide(List.of(), mode))
                .isEqualTo(new CompletionPolicy.Succeed(true));
    }

    // ---- the all-or-nothing default -----------------------------------------

    @Test
    @DisplayName("time-out fails with 504 by default, naming the node")
    void timeoutFailsWith504() {
        assertThat(CompletionPolicy.decide(List.of(ACTIVE, TIMEOUT), Completeness.ALL))
                .isEqualTo(new CompletionPolicy.Fail(FedErrorCode.FED_INCOMPLETE, List.of("n3"), List.of()));
    }

    @Test
    @DisplayName("offline without a response fails with 504 by default")
    void unreachableFailsWith504() {
        assertThat(CompletionPolicy.decide(List.of(OFFLINE), Completeness.ALL))
                .isEqualTo(new CompletionPolicy.Fail(FedErrorCode.FED_INCOMPLETE, List.of("n2"), List.of()));
    }

    @Test
    @DisplayName("a node that answered with an error fails with 424 by default")
    void nodeErrorFailsWith424() {
        assertThat(CompletionPolicy.decide(List.of(ACTIVE, NODE_ERROR), Completeness.ALL))
                .isEqualTo(new CompletionPolicy.Fail(FedErrorCode.FED_NODE_ERROR, List.of(), List.of("n4")));
    }

    /**
     * The spec is silent on a fan-out with both kinds. 504 wins: an unanswered
     * node means <em>unknown</em>, and a retry or {@code partial} is the client's
     * cheapest recovery — while the erroring node is still named so nothing is
     * hidden.
     */
    @Test
    @DisplayName("mixed unanswered and erroring: 504 wins, both are named")
    void unansweredOutranksErroring() {
        assertThat(CompletionPolicy.decide(List.of(NODE_ERROR, TIMEOUT, OFFLINE), Completeness.ALL))
                .isEqualTo(new CompletionPolicy.Fail(FedErrorCode.FED_INCOMPLETE,
                        List.of("n3", "n2"), List.of("n4")));
    }

    @Test
    @DisplayName("a carve-out status beside a failing one does not soften the failure")
    void carveOutBesideFailureStillFails() {
        assertThat(CompletionPolicy.decide(List.of(NOT_RESOLVED, NODE_ERROR), Completeness.ALL))
                .isInstanceOf(CompletionPolicy.Fail.class);
    }

    // ---- opted-in best-effort -------------------------------------------------

    @Test
    @DisplayName("partial: every failing status is reported, none fails")
    void partialNeverFails() {
        assertThat(CompletionPolicy.decide(
                List.of(ACTIVE, OFFLINE, TIMEOUT, NODE_ERROR, NOT_RESOLVED, CONSENT_DENIED, EXCLUDED, NOT_LOCALIZED),
                Completeness.PARTIAL))
                .isEqualTo(new CompletionPolicy.Succeed(false));
    }
}
