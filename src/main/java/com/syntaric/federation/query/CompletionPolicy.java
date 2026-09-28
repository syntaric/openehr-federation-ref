// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.query.fanout.NodeOutcome;

import java.util.ArrayList;
import java.util.List;

/**
 * §11.1 / §11.4: which per-node outcomes clear {@code meta.federation.complete},
 * and which fail the query under the all-or-nothing default.
 *
 * <p>The two questions are separate and the table in §11.1 answers both:
 *
 * <pre>
 * active                       -&gt; nothing
 * excluded, not-localized      -&gt; nothing (never in scope)                §11.1
 * not-resolved, consent-denied -&gt; complete=false, never fails            §11.3
 * time-out                     -&gt; complete=false; unanswered
 * offline                      -&gt; complete=false; node error or unanswered
 * </pre>
 *
 * <p>Under {@link FederationRequestContext.Completeness#PARTIAL} nothing fails:
 * the rows that arrived are returned with the flag cleared. Under the default,
 * any unanswered node fails the query with {@code 504} and any erroring node
 * with {@code 424}. When one fan-out has both kinds the spec is silent;
 * {@code 504} wins here because an unanswered node means <em>unknown</em>, and a
 * retry — or opting into {@code partial} — is the client's cheapest recovery.
 *
 * <p>Pure and static so the table is unit-testable without a fan-out.
 */
public final class CompletionPolicy {

    private CompletionPolicy() {
    }

    /** What the gateway does with a set of outcomes. */
    public sealed interface Decision permits Succeed, Fail {
    }

    /** Answer {@code 200}; {@code complete} is the flag to put in the envelope. */
    public record Succeed(boolean complete) implements Decision {
    }

    /** Fail with {@code code}, naming the nodes that caused it. */
    public record Fail(FedErrorCode code, List<String> unanswered, List<String> erroring) implements Decision {
    }

    public static Decision decide(final List<NodeOutcome> outcomes,
                                  final FederationRequestContext.Completeness mode) {
        final List<String> unanswered = new ArrayList<>();
        final List<String> erroring = new ArrayList<>();
        boolean complete = true;
        for (final NodeOutcome outcome : outcomes) {
            if (!outcome.clearsComplete()) {
                continue;
            }
            complete = false;
            if (outcome.nodeError()) {
                erroring.add(outcome.endpointId());
            } else if (outcome.unanswered()) {
                unanswered.add(outcome.endpointId());
            }
            // not-resolved / consent-denied: flag cleared, nothing to fail (§11.3)
        }
        if (mode == FederationRequestContext.Completeness.PARTIAL) {
            return new Succeed(complete);
        }
        if (!unanswered.isEmpty()) {
            return new Fail(FedErrorCode.FED_INCOMPLETE, List.copyOf(unanswered), List.copyOf(erroring));
        }
        if (!erroring.isEmpty()) {
            return new Fail(FedErrorCode.FED_NODE_ERROR, List.of(), List.copyOf(erroring));
        }
        // Covers the empty registry too: nothing was in scope, so nothing is missing.
        return new Succeed(complete);
    }
}
