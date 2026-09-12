// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.fanout;

import java.util.List;

/**
 * Terminal result of dispatching one node query. {@code status} uses the spec's
 * §11.1 endpoint status vocabulary: active | offline | time-out | not-resolved |
 * consent-denied | excluded | not-localized.
 */
public record NodeOutcome(
        String endpointId,
        String status,
        String error,
        Long latencyMs,
        List<List<Object>> rows,
        List<Column> columns,
        String url) {

    public record Column(String name, String path) {
    }

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_OFFLINE = "offline";
    public static final String STATUS_TIMEOUT = "time-out";
    public static final String STATUS_NOT_RESOLVED = "not-resolved";
    public static final String STATUS_CONSENT_DENIED = "consent-denied";
    /**
     * Ruled out <i>by a decision about this node</i> — a directive that did not
     * name it, or an operator policy. Some authority acted; contrast
     * {@link #STATUS_NOT_LOCALIZED}, where none did (§11.1).
     */
    public static final String STATUS_EXCLUDED = "excluded";

    /**
     * A registry member localization did not return as a candidate for this
     * query (§11.1, §14). The node was never asked and no decision was made
     * about it — not a directive, not an operator policy, not consent. This is
     * the ordinary outcome for an undirected query and is not a failure.
     */
    public static final String STATUS_NOT_LOCALIZED = "not-localized";

    public boolean succeeded() {
        return STATUS_ACTIVE.equals(status);
    }

    /** Node was asked and failed (as opposed to skipped locally). */
    public boolean failed() {
        return STATUS_OFFLINE.equals(status) || STATUS_TIMEOUT.equals(status);
    }

    /**
     * Whether node selection put this node in scope for the query (§11.1
     * <i>What "in scope" means</i>).
     *
     * <p>{@code excluded} and {@code not-localized} were <b>never in scope</b> —
     * one because a decision removed it before selection completed, the other
     * because nothing selected it. Both are still reported, so a client sees the
     * whole federation picture rather than a silently truncated list, but
     * neither clears {@code meta.complete}: a query is not incomplete for
     * failing to ask a node it never intended to ask.
     */
    public boolean inScope() {
        return !STATUS_EXCLUDED.equals(status) && !STATUS_NOT_LOCALIZED.equals(status);
    }

    /**
     * Whether the gateway actually dispatched a query to this endpoint — i.e.
     * whether there was a request to time (§9.5, N40).
     *
     * <p>Distinct from {@link #inScope()}, and deliberately so: {@code
     * not-resolved} is <em>in scope</em> (the node was selected, so its absence
     * from the answer is real coverage information and clears {@code complete})
     * yet was <em>never dispatched to</em> — Step-1 identity resolution settles it
     * before any node query exists. {@code latency_ms} follows dispatch, not
     * scope, which is why it is omitted for those rather than reported as a 0
     * that would read as "answered instantly".
     */
    public boolean dispatched() {
        return STATUS_ACTIVE.equals(status)
                || STATUS_OFFLINE.equals(status)
                || STATUS_TIMEOUT.equals(status);
    }

    public static NodeOutcome success(final String endpointId, final long latencyMs, final List<Column> columns,
                                      final List<List<Object>> rows, final String url) {
        return new NodeOutcome(endpointId, STATUS_ACTIVE, null, latencyMs, rows, columns, url);
    }

    public static NodeOutcome offline(final String endpointId, final long latencyMs, final String error, final String url) {
        return new NodeOutcome(endpointId, STATUS_OFFLINE, error, latencyMs, List.of(), List.of(), url);
    }

    public static NodeOutcome timeout(final String endpointId, final long latencyMs, final String url) {
        return new NodeOutcome(endpointId, STATUS_TIMEOUT, "per-node timeout exceeded", latencyMs,
                List.of(), List.of(), url);
    }

    public static NodeOutcome skipped(final String endpointId, final String status, final String error) {
        return new NodeOutcome(endpointId, status, error, null, List.of(), List.of(), null);
    }
}
