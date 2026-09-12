// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.fanout;

import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.aql.rewrite.NodeQuerySpec;
import com.syntaric.federation.outbound.NodeClient;
import com.syntaric.federation.outbound.NodeClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * N38 fan-out engine: one virtual thread per node, per-node timeout as the
 * HTTP request timeout, an overall budget enforced by the collector latch.
 * Abandoning one node never cancels the others — threads are left to finish
 * and their late answers are discarded by the closed collector.
 *
 * <p><b>This class performs no database work of any kind, and must not start.</b>
 * It used to call {@code RegistryService.recordLatency()} on every node answer —
 * an INSERT, a {@code findTop100…} SELECT and an endpoint UPDATE, per node per
 * query, on the dispatch thread. That was the pre-existing Invariant 0 breach;
 * latency now leaves with {@link NodeOutcome#latencyMs()}, for the caller to
 * read off the response or a listener to persist off the request path. The absence of any
 * registry dependency here is the point, and {@code FanOutNoDbWritesTest} pins
 * it — a constructor argument is much harder to re-add unnoticed than a call.
 */
@Component
public class FanOutExecutor {

    private static final Logger log = LoggerFactory.getLogger(FanOutExecutor.class);

    private final NodeClientFactory clientFactory;

    public FanOutExecutor(final NodeClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public List<NodeOutcome> execute(final List<NodeQuerySpec> specs,
                                     final Map<String, EndpointDescriptor> endpointInfo,
                                     final TimeoutBudget budget,
                                     final String inboundAuthorization) {
        final ResultCollector collector = new ResultCollector(specs.size());
        // Deliberately NOT try-with-resources: ExecutorService.close() would await
        // in-flight node calls past the budget. Abandoned virtual threads run to
        // completion on their own; the closed collector discards their answers.
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        for (final NodeQuerySpec spec : specs) {
            executor.submit(() -> dispatchOne(spec, endpointInfo.get(spec.endpointId()),
                    budget, inboundAuthorization, collector));
        }
        try {
            collector.awaitAndClose(budget.remaining());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            collector.close();
        }
        executor.shutdown();
        return collector.outcomes(specs.stream().map(NodeQuerySpec::endpointId).toList(),
                budget.overall().toMillis());
    }

    private void dispatchOne(final NodeQuerySpec spec, final EndpointDescriptor endpoint, final TimeoutBudget budget,
                             final String inboundAuthorization, final ResultCollector collector) {
        final NodeClient client = clientFactory.forEndpoint(endpoint);
        final Duration timeout = min(budget.perNode(), budget.remaining());
        final long start = System.nanoTime();
        try {
            if (timeout.isZero() || timeout.isNegative()) {
                collector.offer(NodeOutcome.timeout(spec.endpointId(), 0, client.queryUrl()));
                return;
            }
            final NodeClient.NodeQueryResponse response =
                    client.query(spec.aql(), timeout, inboundAuthorization);
            final long latency = elapsedMs(start);
            // A node that omits `name` gets a positional stand-in rather than its
            // own path string: row keys are façade output, and CDRs render paths
            // differently (EHRbase keeps the FROM alias, FerroEHR strips it), so
            // echoing one here would make row keys vendor-dependent. Both demo
            // CDRs already name unaliased columns "#0", "#1"; this matches that.
            final List<NodeClient.NodeQueryResponse.Column> reported = response.columnsOrEmpty();
            final List<NodeOutcome.Column> columns = new ArrayList<>(reported.size());
            for (int i = 0; i < reported.size(); i++) {
                final NodeClient.NodeQueryResponse.Column c = reported.get(i);
                columns.add(new NodeOutcome.Column(c.name() != null ? c.name() : "#" + i, c.path()));
            }
            collector.offer(NodeOutcome.success(spec.endpointId(), latency, columns,
                    response.rowsOrEmpty(), client.queryUrl()));
        } catch (final HttpTimeoutException e) {
            collector.offer(NodeOutcome.timeout(spec.endpointId(), elapsedMs(start), client.queryUrl()));
        } catch (final IOException e) {
            collector.offer(NodeOutcome.offline(spec.endpointId(), elapsedMs(start),
                    e.getMessage(), client.queryUrl()));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            collector.offer(NodeOutcome.offline(spec.endpointId(), elapsedMs(start),
                    "dispatch interrupted", client.queryUrl()));
        } catch (final Exception e) {
            log.warn("Unexpected dispatch failure for endpoint {}", spec.endpointId(), e);
            collector.offer(NodeOutcome.offline(spec.endpointId(), elapsedMs(start),
                    "unexpected dispatch failure", client.queryUrl()));
        }
    }

    private static long elapsedMs(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static Duration min(final Duration a, final Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
