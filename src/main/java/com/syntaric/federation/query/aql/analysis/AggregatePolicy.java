// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.analysis;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.dto.operand.AggregateFunction;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;

/**
 * N14/N39: an undirected aggregate must be rejected with 400 and a reason —
 * never answered with per-node aggregate rows. A directed single-node aggregate
 * is dispatched unchanged. Decomposable aggregates are deferred in v1.
 */
public final class AggregatePolicy {

    private AggregatePolicy() {
    }

    public static void check(final AqlQuery query, final int targetNodeCount) {
        final boolean hasAggregate = query.getSelect().getStatement().stream()
                .map(SelectExpression::getColumnExpression)
                .anyMatch(AggregateFunction.class::isInstance);
        if (hasAggregate && targetNodeCount > 1) {
            throw new FederationException(FedErrorCode.FED_AGGREGATE_UNSUPPORTED,
                    "Aggregate functions cannot be computed correctly across a fan-out to "
                            + targetNodeCount + " nodes. Either direct the query to a single node "
                            + "(FROM ENDPOINT / openEHR-federation-endpoint), or select the underlying "
                            + "rows and aggregate in the application.");
        }
    }
}
