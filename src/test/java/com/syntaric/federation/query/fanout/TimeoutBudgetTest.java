// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.fanout;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** N38: Prefer: wait may shorten the budget, never extend it. */
@Tag("CP-31")
class TimeoutBudgetTest {

    @Test
    void preferWaitShortensTheBudget() {
        TimeoutBudget budget = TimeoutBudget.start(
                Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(5));
        assertThat(budget.overall()).isEqualTo(Duration.ofSeconds(5));
        assertThat(budget.perNode()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void preferWaitCannotExtendTheBudget() {
        TimeoutBudget budget = TimeoutBudget.start(
                Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(60));
        assertThat(budget.overall()).isEqualTo(Duration.ofSeconds(15));
        assertThat(budget.perNode()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void absentPreferKeepsConfiguredValues() {
        TimeoutBudget budget = TimeoutBudget.start(
                Duration.ofSeconds(10), Duration.ofSeconds(15), null);
        assertThat(budget.overall()).isEqualTo(Duration.ofSeconds(15));
        assertThat(budget.remaining()).isLessThanOrEqualTo(Duration.ofSeconds(15));
    }
}
