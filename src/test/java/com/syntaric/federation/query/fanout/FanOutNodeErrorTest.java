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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §11.4 needs the fan-out to tell "did not answer" from "answered with an
 * error", because the two fail the query with different statuses (504 vs 424).
 * This pins the catch-site distinction: a node's HTTP error is reported
 * {@code offline} on the wire — the closed §11.1 vocabulary has nothing better —
 * with {@link NodeOutcome.Failure#NODE_ERROR} behind it, and the node's own
 * status and body in {@code error}.
 */
@Tag("CP-30")
@Tag("CP-11")
class FanOutNodeErrorTest {

    private HttpServer node;

    @BeforeEach
    void startNode() throws IOException {
        node = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        node.createContext("/v1/query/aql", exchange -> {
            final byte[] body = "{\"error\":\"boom\",\n\"message\":\"disk on fire\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
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
    @DisplayName("a node's HTTP error is offline + NODE_ERROR with the node's body in error")
    void httpErrorIsANodeErrorNotUnreachable() {
        final FederationProperties properties = new FederationProperties(
                null,
                new FederationProperties.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(3),
                        Duration.ofMillis(500)),
                null, null,
                new FederationProperties.Security("passthrough", Map.of(), null),
                null, null);
        final FanOutExecutor executor = new FanOutExecutor(new NodeClientFactory(properties,
                new OutboundAuthProviders(List.of(new PassthroughAuthProvider()), properties)));
        final String url = "http://127.0.0.1:" + node.getAddress().getPort();
        final EndpointDescriptor endpoint = new EndpointDescriptor(
                "ep-1", "node-1", "cdr1.test", "Org", url, null, null, null, null);

        final List<NodeOutcome> outcomes = executor.execute(
                List.of(new NodeQuerySpec("ep-1", "node-1", url, null, "SELECT c FROM EHR e")),
                Map.of("ep-1", endpoint),
                TimeoutBudget.start(Duration.ofSeconds(2), Duration.ofSeconds(3), null),
                null);

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(NodeOutcome.STATUS_OFFLINE);
            assertThat(outcome.failure()).isEqualTo(NodeOutcome.Failure.NODE_ERROR);
            assertThat(outcome.nodeError()).isTrue();
            assertThat(outcome.unanswered()).isFalse();
            assertThat(outcome.clearsComplete()).isTrue();
            assertThat(outcome.error())
                    .contains("500")
                    .contains("disk on fire")
                    .as("control characters in the node body must not survive into the envelope")
                    .doesNotContain("\n");
            assertThat(outcome.rows()).isEmpty();
        });
    }

    @Test
    @DisplayName("a connection refused is offline + UNREACHABLE — the 504 class")
    void connectionRefusedIsUnreachable() {
        node.stop(0);
        final FederationProperties properties = new FederationProperties(
                null,
                new FederationProperties.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(3),
                        Duration.ofMillis(500)),
                null, null,
                new FederationProperties.Security("passthrough", Map.of(), null),
                null, null);
        final FanOutExecutor executor = new FanOutExecutor(new NodeClientFactory(properties,
                new OutboundAuthProviders(List.of(new PassthroughAuthProvider()), properties)));
        final String url = "http://127.0.0.1:" + node.getAddress().getPort();
        final EndpointDescriptor endpoint = new EndpointDescriptor(
                "ep-1", "node-1", "cdr1.test", "Org", url, null, null, null, null);

        final List<NodeOutcome> outcomes = executor.execute(
                List.of(new NodeQuerySpec("ep-1", "node-1", url, null, "SELECT c FROM EHR e")),
                Map.of("ep-1", endpoint),
                TimeoutBudget.start(Duration.ofSeconds(2), Duration.ofSeconds(3), null),
                null);

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(NodeOutcome.STATUS_OFFLINE);
            assertThat(outcome.failure()).isEqualTo(NodeOutcome.Failure.UNREACHABLE);
            assertThat(outcome.unanswered()).isTrue();
            assertThat(outcome.nodeError()).isFalse();
            // The schema requires `error` on every offline entry, and the JDK's
            // ConnectException carries an empty message — caught against a
            // stopped demo node, so pinned here.
            assertThat(outcome.error()).isNotBlank();
        });
    }

    @Test
    @DisplayName("an exception with no message still yields a non-blank error")
    void blankExceptionMessageIsNeverReportedAsBlank() {
        assertThat(NodeOutcome.offline("ep", 1, null, "http://x").error()).isNotBlank();
        assertThat(NodeOutcome.offline("ep", 1, "  ", "http://x").error()).isNotBlank();
        assertThat(NodeOutcome.offline("ep", 1, "Connection reset", "http://x").error())
                .isEqualTo("Connection reset");
    }
}
