// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.merge;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.fanout.NodeOutcome;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.dto.operand.IdentifiedPath;
import org.ehrbase.openehr.sdk.aql.dto.orderby.OrderByExpression;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * N39/§11.6.1: the tier re-applies ORDER BY across merged rows with
 * deterministic tie-breaking — endpoint_id, then uid, then the full row
 * rendering as an absolute last resort.
 */
public final class OrderByComparator {

    private OrderByComparator() {
    }

    /**
     * Builds a comparator from the query's ORDER BY, mapped onto envelope column
     * names. Every ORDER BY path must correspond to a selected column — matched
     * structurally against the façade's own SELECT list, never against the path
     * string a node reported. Fail closed otherwise.
     */
    public static Comparator<RowWithProvenance> forQuery(final AqlQuery query, final List<NodeOutcome.Column> columns) {
        final List<Comparator<RowWithProvenance>> keys = new ArrayList<>();
        for (final OrderByExpression order : query.getOrderBy() == null
                ? List.<OrderByExpression>of() : query.getOrderBy()) {
            final String columnName = resolveColumn(order.getStatement(), query, columns);
            final boolean descending = order.getSymbol() == OrderByExpression.OrderByDirection.DESC;
            final Comparator<RowWithProvenance> key =
                    Comparator.comparing(r -> r.values().get(columnName), OrderByComparator::compareValues);
            keys.add(descending ? key.reversed() : key);
        }
        final Comparator<RowWithProvenance> result = keys.isEmpty()
                ? (a, b) -> 0
                : keys.stream().reduce(Comparator::thenComparing).orElseThrow();
        return result
                .thenComparing(RowWithProvenance::endpointId)
                .thenComparing(r -> {
                    final RowWithProvenance.VersionUid uid = r.versionUid();
                    return uid == null ? "" : uid.objectId() + "::" + uid.creatingSystemId()
                            + "::" + uid.versionTreeId();
                })
                .thenComparing(r -> String.valueOf(r.values()));
    }

    /**
     * Maps one ORDER BY term to the envelope column name it sorts on.
     *
     * <p>Matching is done against the façade's own SELECT list — the same
     * {@link AqlQuery} instance the rewriter dispatched, so its statement list is
     * positionally 1:1 with the columns a node returns — and the column name is
     * then taken from that position. The path string a node reported is never
     * compared, because CDRs do not agree on how to render it: against the demo
     * stack, EHRbase echoes the FROM alias ({@code c/context/start_time/value})
     * while FerroEHR strips it ({@code /context/start_time/value}). Any fix that
     * compared rendered strings — including stripping the alias from our side —
     * would necessarily match one vendor and fail the other. Structural matching
     * depends on nothing the node chooses.
     */
    private static String resolveColumn(final IdentifiedPath order, final AqlQuery query,
                                        final List<NodeOutcome.Column> columns) {
        final List<SelectExpression> selects = query.getSelect() == null
                ? List.<SelectExpression>of() : query.getSelect().getStatement();
        for (int i = 0; i < selects.size(); i++) {
            if (!(selects.get(i).getColumnExpression() instanceof IdentifiedPath selected)
                    || !sameTerm(order, selected)) {
                continue;
            }
            // Prefer the node's own name for that position; fall back to the
            // SELECT alias when a node returned fewer columns than we selected.
            if (i < columns.size()) {
                return columns.get(i).name();
            }
            if (selects.get(i).getAlias() != null) {
                return selects.get(i).getAlias();
            }
            break;
        }
        throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED,
                "ORDER BY path '" + order.render()
                        + "' must appear in the SELECT list of a federated query");
    }

    /**
     * Structural equality of two identified paths: same FROM variable and same
     * attribute path. Both sides come from our own parse of the façade AQL, so
     * this compares like with like regardless of node rendering.
     */
    private static boolean sameTerm(final IdentifiedPath a, final IdentifiedPath b) {
        final String rootA = a.getRoot() == null ? null : a.getRoot().getIdentifier();
        final String rootB = b.getRoot() == null ? null : b.getRoot().getIdentifier();
        if (!Objects.equals(rootA, rootB)) {
            return false;
        }
        final String pathA = a.getPath() == null ? null : a.getPath().render();
        final String pathB = b.getPath() == null ? null : b.getPath().render();
        return Objects.equals(pathA, pathB);
    }

    /** Nulls last; numbers numerically; everything else by string form. */
    static int compareValues(final Object a, final Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return new BigDecimal(na.toString()).compareTo(new BigDecimal(nb.toString()));
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
