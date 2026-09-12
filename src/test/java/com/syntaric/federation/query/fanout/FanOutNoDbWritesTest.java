// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.fanout;

import com.sun.net.httpserver.HttpServer;
import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.aql.rewrite.NodeQuerySpec;
import com.syntaric.federation.outbound.NodeClientFactory;
import com.syntaric.federation.outbound.auth.OutboundAuthProviders;
import com.syntaric.federation.outbound.auth.PassthroughAuthProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Invariant 0, rule 2 — the fan-out performs no synchronous database work.</b>
 *
 * <p>This is the regression guard for the breach M2 removed: {@code sampleLatency}
 * called {@code RegistryService.recordLatency()} on the dispatch thread, doing an
 * INSERT, a {@code findTop100…} SELECT and an endpoint UPDATE per node per query.
 * It was exception-wrapped, so it satisfied "never fail" and nothing in the suite
 * noticed it violated "never delay".
 *
 * <p>Two assertions, because either alone can be defeated:
 *
 * <ul>
 *   <li><b>Structural</b> — {@code FanOutExecutor} holds no collaborator that can
 *       reach persistence. A behavioural test passes happily against a dependency
 *       that merely happens not to be exercised by the fixture.</li>
 *   <li><b>Behavioural</b> — a real fan-out against a real HTTP node completes
 *       with no database in the context at all. A structural test passes happily
 *       against a static call or a service-locator lookup.</li>
 * </ul>
 *
 * <p>Deliberately not a {@code @SpringBootTest}: the point is that this path needs
 * no database, and a test that boots one cannot demonstrate that.
 */
@Tag("CP-31")
class FanOutNoDbWritesTest {

    private HttpServer node;
    private final AtomicInteger nodeCalls = new AtomicInteger();

    @BeforeEach
    void startNode() throws IOException {
        node = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        node.createContext("/v1/query/aql", exchange -> {
            nodeCalls.incrementAndGet();
            byte[] body = """
                    {"q":"dispatched","columns":[{"name":"uid","path":"c/uid/value"}],
                     "rows":[["uid-1"],["uid-2"]]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        node.start();
    }

    @AfterEach
    void stopNode() {
        node.stop(0);
    }

    @Test
    @DisplayName("FanOutExecutor holds no collaborator that can reach the database")
    void fanOutHasNoPersistenceCollaborators() throws Exception {
        for (Field field : FanOutExecutor.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String type = field.getType().getName();
            assertThat(type)
                    .as("FanOutExecutor.%s — the fan-out must reach no persistence "
                            + "(Invariant 0 rule 2); this is how recordLatency got onto "
                            + "the request thread", field.getName())
                    .doesNotContain("registry")
                    .doesNotContain("monitoring")
                    .doesNotContain("Repository")
                    .doesNotContain("javax.sql")
                    .doesNotContain("java.sql")
                    .doesNotContain("org.springframework.data")
                    .doesNotContain("org.springframework.jdbc");
        }
    }

    @Test
    @DisplayName("a full fan-out completes with no DataSource anywhere in reach")
    void fanOutRunsWithoutADatabase() {
        FederationProperties properties = new FederationProperties(
                null,
                new FederationProperties.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(3),
                        Duration.ofMillis(500)),
                null,
                null,
                new FederationProperties.Security("passthrough", Map.of(), null),
                null);
        // The executor is constructed by hand with its only real collaborator. If
        // a persistence dependency is ever re-added, this line stops compiling —
        // which is the earliest and loudest place for it to fail.
        FanOutExecutor executor = new FanOutExecutor(new NodeClientFactory(properties,
                new OutboundAuthProviders(List.of(new PassthroughAuthProvider()), properties)));

        String url = "http://127.0.0.1:" + node.getAddress().getPort();
        EndpointDescriptor endpoint = new EndpointDescriptor(
                "ep-1", "node-1", "cdr1.test", "Org", url, null, null, null, null);

        List<NodeOutcome> outcomes = executor.execute(
                List.of(new NodeQuerySpec("ep-1", "node-1", url, null, "SELECT c FROM EHR e")),
                Map.of("ep-1", endpoint),
                TimeoutBudget.start(Duration.ofSeconds(2), Duration.ofSeconds(3), null),
                null);

        assertThat(nodeCalls).hasValue(1);
        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(NodeOutcome.STATUS_ACTIVE);
            // Latency is still measured — it now leaves with the outcome instead
            // of being written from here.
            assertThat(outcome.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
            assertThat(outcome.rows()).hasSize(2);
        });
    }
}
