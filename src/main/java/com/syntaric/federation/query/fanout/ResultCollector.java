// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.fanout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Collects per-node outcomes until either every node answered or the overall
 * budget expires. After {@link #close()} late {@link #offer}s become no-ops: the
 * answer arrived past the budget and the envelope has already been decided.
 *
 * <p>This class used to carry a latency sink invoked on every offer, which put a
 * synchronous {@code INSERT + SELECT + UPDATE} on the dispatch thread — the
 * Invariant 0 breach M2 exists to remove. Latency now travels out with the
 * outcome itself, to be reported or persisted off the request path; a late
 * answer's latency is lost along with its rows, which is the correct trade (a
 * sample nobody waited for is worth less than a dispatch thread doing no I/O).
 */
public final class ResultCollector {

    private final Map<String, NodeOutcome> outcomes = new ConcurrentHashMap<>();
    private final CountDownLatch latch;
    private volatile boolean closed;

    public ResultCollector(final int expected) {
        this.latch = new CountDownLatch(expected);
    }

    public void offer(final NodeOutcome outcome) {
        if (!closed) {
            outcomes.putIfAbsent(outcome.endpointId(), outcome);
        }
        latch.countDown();
    }

    /** Waits until all nodes answered or the budget ran out, then closes. */
    public void awaitAndClose(final Duration budgetRemaining) throws InterruptedException {
        //noinspection ResultOfMethodCallIgnored — a false return simply means the budget expired
        latch.await(Math.max(0, budgetRemaining.toMillis()), TimeUnit.MILLISECONDS);
        closed = true;
    }

    public void close() {
        closed = true;
    }

    /**
     * Outcomes received before close, plus synthetic time-outs for nodes still
     * silent at abandonment ({@code latency_ms} = elapsed time at abandonment,
     * per §9.4).
     */
    public List<NodeOutcome> outcomes(final List<String> expectedEndpointIds, final long abandonedAfterMs) {
        final List<NodeOutcome> result = new ArrayList<>();
        for (final String endpointId : expectedEndpointIds) {
            result.add(outcomes.getOrDefault(endpointId,
                    NodeOutcome.timeout(endpointId, abandonedAfterMs, null)));
        }
        return result;
    }
}
