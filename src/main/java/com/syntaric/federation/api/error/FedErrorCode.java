// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api.error;

import org.springframework.http.HttpStatus;

/**
 * Internal error currency of the federation tier. Every federation failure maps to
 * exactly one code with a fixed HTTP status.
 */
public enum FedErrorCode {

    /** Directive and header target sets both present but differ. */
    FED_TARGET_CONFLICT(HttpStatus.BAD_REQUEST),
    /** Referenced endpoint/organisation is not a member of this federation. */
    FED_UNKNOWN_TARGET(HttpStatus.BAD_REQUEST),
    /** AQL could not be parsed at all. */
    FED_AQL_INVALID(HttpStatus.BAD_REQUEST),
    /** Subject predicate present but cannot be safely stripped/rewritten. */
    FED_IDENTIFIER_UNSTRIPPABLE(HttpStatus.BAD_REQUEST),
    /** A directly identifying identifier would reach a node (N33 final gate). */
    FED_IDENTIFIER_HYGIENE(HttpStatus.BAD_REQUEST),
    /** Undirected aggregate function in a fan-out query. */
    FED_AGGREGATE_UNSUPPORTED(HttpStatus.BAD_REQUEST),
    /** OFFSET > 0 is not supported at the federation tier (v1 choice). */
    FED_OFFSET_UNSUPPORTED(HttpStatus.BAD_REQUEST),
    /** Query construct outside the verified rewrite envelope; fail closed. */
    FED_QUERY_UNSUPPORTED(HttpStatus.BAD_REQUEST),
    /** New-object creation without an explicit target endpoint. */
    FED_NO_TARGET(HttpStatus.BAD_REQUEST),
    /** Malformed federation header. */
    FED_HEADER_INVALID(HttpStatus.BAD_REQUEST),
    /**
     * A registry write carries a field the registry will not accept — today, a
     * probe target with a method outside {@code OPTIONS|GET|HEAD}.
     *
     * <p>The admin DTOs already reject this with bean validation, which gives the
     * console its per-field {@code errors} map. This code covers the path that has
     * no DTO: {@code POST /admin/registry/import} binds records straight from the
     * document, so without it a hand-edited export would persist a probe target
     * that fails at sweep time instead of at import time.
     */
    FED_REGISTRY_INVALID(HttpStatus.BAD_REQUEST),

    /** Versioned write routed to a node that is not the controlling system. */
    FED_WRONG_CONTROLLING_SYSTEM(HttpStatus.CONFLICT),
    /** Same ehr_id claimed by two different nodes. */
    FED_EHR_ID_COLLISION(HttpStatus.CONFLICT),

    /** Resource/route cannot be resolved to any node. */
    FED_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** all-or-nothing completeness requested and at least one node failed. */
    FED_INCOMPLETE(HttpStatus.GATEWAY_TIMEOUT),

    /** Feature defined by the spec but not offered by this deployment. */
    FED_NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED),

    /**
     * A single-node route could not reach its node at all — connection refused,
     * I/O failure, interrupted transfer.
     *
     * <p>{@code 502} here is <b>not</b> a deviation from §11.2's "node internal
     * error → 500, passed through". That rule governs a node that answered with
     * an error of its own, and §11.2 is explicit that 500 is correct there
     * because "the error is the destination's, and {@code 502 Bad Gateway} would
     * attribute it to the intermediary". We honour that: a node's own status is
     * streamed through untouched ({@code ProxyService}), and in fan-out a node
     * error becomes a per-endpoint status rather than an HTTP status.
     *
     * <p>This code covers the different case where there was no node response to
     * pass through. The failure genuinely is the intermediary's to report, and
     * §11.2 has no row for it on a single-node route.
     */
    FED_UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY);

    private final HttpStatus status;

    FedErrorCode(final HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
