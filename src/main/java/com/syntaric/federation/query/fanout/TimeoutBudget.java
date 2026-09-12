// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.fanout;

import java.time.Duration;
import java.time.Instant;

/**
 * N38: a per-node timeout plus an overall budget for the whole fan-out.
 * {@code Prefer: wait=N} may only shorten the configured budget, never extend it.
 */
public record TimeoutBudget(Duration perNode, Duration overall, Instant startedAt) {

    public static TimeoutBudget start(final Duration perNode, final Duration overall, final Duration preferWait) {
        Duration effectiveOverall = overall;
        if (preferWait != null && preferWait.compareTo(overall) < 0 && !preferWait.isNegative()
                && !preferWait.isZero()) {
            effectiveOverall = preferWait;
        }
        final Duration effectivePerNode = perNode.compareTo(effectiveOverall) < 0 ? perNode : effectiveOverall;
        return new TimeoutBudget(effectivePerNode, effectiveOverall, Instant.now());
    }

    public Duration remaining() {
        final Duration elapsed = Duration.between(startedAt, Instant.now());
        final Duration remaining = overall.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
