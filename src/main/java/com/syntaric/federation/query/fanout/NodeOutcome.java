// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.fanout;

import java.util.List;

/**
 * Terminal result of dispatching one node query. {@code status} uses the spec's
 * §11.1 endpoint status vocabulary: active | offline | time-out | not-resolved |
 * consent-denied | excluded | not-localized.
 *
 * <p>{@code failure} is internal bookkeeping the wire vocabulary cannot carry:
 * §11.1 has no status for "asked, answered with an error", yet §11.4 fails such
 * a query with {@code 424} where an unanswered node fails it with {@code 504}.
 * The status stays within the closed seven-value set the published schema
 * enforces; the failure kind is what {@code CompletionPolicy} reads to pick the
 * HTTP status.
 */
public record NodeOutcome(
        String endpointId,
        String status,
        String error,
        Long latencyMs,
        List<List<Object>> rows,
        List<Column> columns,
        String url,
        Failure failure) {

    public record Column(String name, String path) {
    }

    /**
     * Why a dispatched node did not reach {@code active}. Null for {@code active}
     * and for every status settled before dispatch.
     */
    public enum Failure {
        /** No HTTP response at all: connection refused, reset, DNS, interrupted transfer. */
        UNREACHABLE,
        /** The per-node timeout or the overall budget expired first. */
        TIMEOUT,
        /** The node answered, with a non-200 status of its own (§11.4: {@code 424}). */
        NODE_ERROR
    }

    public static final String STATUS_ACTIVE = "active";

    /**
     * Known node, not reachable — and, on this gateway, also a node that
     * <em>answered with an HTTP error</em>.
     *
     * <p>That second use is a deliberate reading of a gap in §11.1: the closed
     * seven-value status set has no member for "asked, answered with a failure",
     * while §11.4 treats that case as its own failure class ({@code 424} rather
     * than {@code 504}). Rather than invent an eighth value the published schema
     * would reject, the node is reported {@code offline} with the node's own
     * status and message in {@code error}, and {@link #failure()} records
     * {@link Failure#NODE_ERROR} so the HTTP status is chosen correctly. The gap
     * has been raised with the specification authors.
     *
     * <p>A node {@code 403} after dispatch is a node error like any other, never
     * {@link #STATUS_CONSENT_DENIED}: the gateway cannot tell a consent refusal
     * from a misconfigured authorization server, and asserting the former would
     * report a consent decision nobody made (§14.4). {@code consent-denied} is
     * claimed only by the Step-1 pre-filter, which is the one path where a
     * consent authority actually spoke (N27a).
     */
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
     * neither clears {@code meta.federation.complete}: a query is not incomplete
     * for failing to ask a node it never intended to ask.
     */
    public boolean inScope() {
        return !STATUS_EXCLUDED.equals(status) && !STATUS_NOT_LOCALIZED.equals(status);
    }

    /**
     * Whether this outcome clears {@code meta.federation.complete} (§11.4, N37):
     * in scope, and short of {@code active}. Clearing the flag and failing the
     * query are different questions — {@code not-resolved} and
     * {@code consent-denied} do the first and never the second (§11.3).
     */
    public boolean clearsComplete() {
        return inScope() && !succeeded();
    }

    /** Asked and never answered: {@code offline} without a response, or {@code time-out} (§11.4: {@code 504}). */
    public boolean unanswered() {
        return failure == Failure.UNREACHABLE || failure == Failure.TIMEOUT;
    }

    /** Asked and answered with an error of the node's own (§11.4: {@code 424}). */
    public boolean nodeError() {
        return failure == Failure.NODE_ERROR;
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
        return new NodeOutcome(endpointId, STATUS_ACTIVE, null, latencyMs, rows, columns, url, null);
    }

    /**
     * Not reachable. {@code error} is never left blank: the published schema
     * requires it on every {@code offline} entry, and a caller passing an
     * exception's own message cannot know whether that message was empty.
     */
    public static NodeOutcome offline(final String endpointId, final long latencyMs, final String error, final String url) {
        final String reported = error == null || error.isBlank() ? "node unreachable" : error;
        return new NodeOutcome(endpointId, STATUS_OFFLINE, reported, latencyMs, List.of(), List.of(), url,
                Failure.UNREACHABLE);
    }

    public static NodeOutcome timeout(final String endpointId, final long latencyMs, final String url) {
        return new NodeOutcome(endpointId, STATUS_TIMEOUT, "per-node timeout exceeded", latencyMs,
                List.of(), List.of(), url, Failure.TIMEOUT);
    }

    /**
     * The node answered with {@code httpStatus}. Reported {@code offline} on the
     * wire (see {@link #STATUS_OFFLINE}) with the node's status and, when it sent
     * one, the start of its body in {@code error} — the per-endpoint detail §11.2
     * lets a gateway carry.
     */
    public static NodeOutcome nodeError(final String endpointId, final long latencyMs, final int httpStatus,
                                        final String body, final String url) {
        final String error = body == null || body.isBlank()
                ? "Node returned HTTP " + httpStatus
                : "Node returned HTTP " + httpStatus + ": " + body;
        return new NodeOutcome(endpointId, STATUS_OFFLINE, error, latencyMs, List.of(), List.of(), url,
                Failure.NODE_ERROR);
    }

    public static NodeOutcome skipped(final String endpointId, final String status, final String error) {
        return new NodeOutcome(endpointId, status, error, null, List.of(), List.of(), null, null);
    }
}
