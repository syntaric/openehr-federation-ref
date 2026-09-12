// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.merge;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.aql.analysis.SubjectAnalysis;
import com.syntaric.federation.query.aql.directive.PreprocessedAql;
import com.syntaric.federation.query.fanout.NodeOutcome;
import com.syntaric.federation.query.fanout.TimeoutBudget;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.dto.operand.IdentifiedPath;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges per-node outcomes into the federated result envelope: tier-level
 * dedup / DISTINCT / ORDER BY / LIMIT (N39), endpoint and subject column
 * re-injection, and the §9.4 meta block.
 */
public final class FederatedResultAssembler {

    private FederatedResultAssembler() {
    }

    public record Assembly(
            String facadeAql,
            AqlQuery query,
            List<NodeOutcome> outcomes,
            Map<String, EndpointDescriptor> endpointInfo,
            List<PreprocessedAql.EndpointProjection> endpointProjections,
            List<SubjectAnalysis.SubjectProjection> subjectProjections,
            String subjectId,
            String dedupMode,
            TimeoutBudget budget,
            long configuredOverallMs) {
    }

    public static ResultEnvelope assemble(final Assembly assembly) {
        final List<NodeOutcome> outcomes = assembly.outcomes();
        final NodeOutcome firstSuccess = outcomes.stream()
                .filter(NodeOutcome::succeeded).findFirst().orElse(null);
        final List<NodeOutcome.Column> nodeColumns = firstSuccess != null ? firstSuccess.columns() : List.of();

        List<RowWithProvenance> merged = new ArrayList<>();
        for (final NodeOutcome outcome : outcomes) {
            if (!outcome.succeeded()) {
                continue;
            }
            for (final List<Object> row : outcome.rows()) {
                final Map<String, Object> values = new LinkedHashMap<>();
                final List<NodeOutcome.Column> cols = outcome.columns();
                for (int i = 0; i < cols.size() && i < row.size(); i++) {
                    values.put(cols.get(i).name(), row.get(i));
                }
                merged.add(new RowWithProvenance(outcome.endpointId(), values));
            }
        }

        // 1. opt-in version-identity dedup (§10.2)
        List<ResultEnvelope.Dedup.Suppressed> suppressed = List.of();
        if ("version-identity".equals(assembly.dedupMode())) {
            final VersionIdentityDeduplicator.Result dedup = VersionIdentityDeduplicator.deduplicate(
                    merged,
                    endpointId -> {
                        final EndpointDescriptor d = assembly.endpointInfo().get(endpointId);
                        return d == null ? null : d.systemId();
                    });
            merged = dedup.rows();
            suppressed = dedup.suppressed();
        }

        // 2. tier-level DISTINCT (N13)
        if (assembly.query().getSelect().isDistinct()) {
            final Set<Map<String, Object>> seen = new LinkedHashSet<>();
            merged = merged.stream().filter(r -> seen.add(r.values())).toList();
        }

        // 3. tier-level ORDER BY with deterministic ties (N39); nothing to sort
        //    (and no columns to map against) when no node contributed rows
        if (!merged.isEmpty()
                && assembly.query().getOrderBy() != null && !assembly.query().getOrderBy().isEmpty()) {
            final Comparator<RowWithProvenance> comparator =
                    OrderByComparator.forQuery(assembly.query(), nodeColumns);
            merged = merged.stream().sorted(comparator).toList();
        }

        // 4. tier-level re-LIMIT (N39)
        final Long limit = assembly.query().getLimit();
        if (limit != null && merged.size() > limit) {
            merged = merged.subList(0, limit.intValue());
        }

        final List<ResultEnvelope.Column> columns = buildColumns(assembly, nodeColumns);

        // Rows go on the wire as ORDERED ARRAYS, positionally matching columns[]
        // (ITS-REST ResultSetRow, §9.1/§9.4). projectRow still returns a name-keyed
        // map because that is the shape the merge steps above addressed values by;
        // flattening happens here, once, against the column list just built — so
        // rows[n][i] is the value of columns[i] by construction rather than by
        // convention.
        final List<List<Object>> rows = merged.stream()
                .map(row -> toPositionalRow(projectRow(row, assembly, nodeColumns), columns))
                .toList();

        return new ResultEnvelope(assembly.facadeAql(), columns, rows, buildMeta(assembly, suppressed));
    }

    // ---- columns -------------------------------------------------------------

    private static List<ResultEnvelope.Column> buildColumns(final Assembly assembly,
                                                            final List<NodeOutcome.Column> nodeColumns) {
        final List<ResultEnvelope.Column> columns = new ArrayList<>();
        for (int i = 0; i < nodeColumns.size(); i++) {
            columns.add(new ResultEnvelope.Column(
                    nodeColumns.get(i).name(), facadePathAt(assembly, i, nodeColumns.get(i))));
        }

        // subject projections back at their positions in the stripped select list
        assembly.subjectProjections().stream()
                .sorted(Comparator.comparingInt(SubjectAnalysis.SubjectProjection::position))
                .forEach(p -> columns.add(Math.min(p.position(), columns.size()),
                        new ResultEnvelope.Column(p.columnName(),
                                "e/" + com.syntaric.federation.query.aql.analysis.SubjectPredicateExtractor.SUBJECT_PATH)));

        // endpoint projections at their positions in the original façade select list
        assembly.endpointProjections().stream()
                .sorted(Comparator.comparingInt(PreprocessedAql.EndpointProjection::position))
                .forEach(p -> columns.add(Math.min(p.position(), columns.size()),
                        new ResultEnvelope.Column(p.columnName(), "p/" + p.attribute().path())));

        final Set<String> names = new LinkedHashSet<>();
        for (final ResultEnvelope.Column column : columns) {
            if (!names.add(column.name())) {
                throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED,
                        "Column name collision on '" + column.name()
                                + "'; alias the ENDPOINT attribute to a distinct name (N18)");
            }
        }
        return columns;
    }

    /**
     * The {@code path} for a node-sourced column, rendered from the façade's own
     * SELECT list rather than echoed from the node.
     *
     * <p>CDRs disagree on how they report {@code columns[].path}: for the same
     * query EHRbase answers {@code c/context/start_time/value} (FROM alias kept)
     * while FerroEHR answers {@code /context/start_time/value} (alias stripped).
     * Echoing that through made the façade's own output depend on which node
     * happened to answer first — the same vendor split that broke ORDER BY, and
     * the reason {@link OrderByComparator} matches structurally. Subject and
     * ENDPOINT projection paths below are already rendered façade-side; this
     * makes node columns consistent with them.
     *
     * <p>Position is the join key: the rewriter dispatches this exact SELECT
     * list, so element {@code i} of it produced column {@code i}. If a node ever
     * returns more columns than we selected, fall back to its own path rather
     * than inventing one.
     */
    private static String facadePathAt(final Assembly assembly, final int index, final NodeOutcome.Column nodeColumn) {
        final List<SelectExpression> selects = assembly.query() == null || assembly.query().getSelect() == null
                ? List.<SelectExpression>of() : assembly.query().getSelect().getStatement();
        if (index < selects.size()
                && selects.get(index).getColumnExpression() instanceof IdentifiedPath selected) {
            return selected.render();
        }
        return nodeColumn.path();
    }

    private static Map<String, Object> projectRow(final RowWithProvenance row, final Assembly assembly,
                                                  final List<NodeOutcome.Column> nodeColumns) {
        final EndpointDescriptor descriptor = assembly.endpointInfo().get(row.endpointId());
        final List<Map.Entry<String, Object>> ordered = new ArrayList<>();
        for (final NodeOutcome.Column column : nodeColumns) {
            ordered.add(Map.entry(column.name(), orNull(row.values().get(column.name()))));
        }
        final List<Map.Entry<String, Object>> withSubject = new ArrayList<>(ordered);
        assembly.subjectProjections().stream()
                .sorted(Comparator.comparingInt(SubjectAnalysis.SubjectProjection::position))
                .forEach(p -> withSubject.add(Math.min(p.position(), withSubject.size()),
                        Map.entry(p.columnName(), (Object) assembly.subjectId())));
        assembly.endpointProjections().stream()
                .sorted(Comparator.comparingInt(PreprocessedAql.EndpointProjection::position))
                .forEach(p -> withSubject.add(Math.min(p.position(), withSubject.size()),
                        Map.entry(p.columnName(), endpointAttribute(p.attribute(), descriptor))));

        final Map<String, Object> result = new LinkedHashMap<>();
        withSubject.forEach(e -> result.put(e.getKey(), e.getValue() == NULL ? null : e.getValue()));
        return result;
    }

    /**
     * Flattens a name-keyed projected row to the positional array ITS-REST
     * requires: element {@code i} is the value of {@code columns[i]} (§9.1).
     *
     * <p>Driven by {@code columns} rather than by the map's own iteration order:
     * the two agree today, but only the column list is the published contract, and
     * a row whose order depended on a {@code LinkedHashMap} would be one refactor
     * away from silently transposing a result set. A column with no value in the
     * map yields {@code null} — a positional row cannot omit an element without
     * shifting every value after it.
     */
    private static List<Object> toPositionalRow(final Map<String, Object> values,
                                                final List<ResultEnvelope.Column> columns) {
        final List<Object> row = new ArrayList<>(columns.size());
        for (final ResultEnvelope.Column column : columns) {
            row.add(values.get(column.name()));
        }
        return row;
    }

    private static final Object NULL = new Object();

    private static Object orNull(final Object value) {
        return value == null ? NULL : value;
    }

    private static Object endpointAttribute(final PreprocessedAql.EndpointProjection.Attribute attribute,
                                            final EndpointDescriptor descriptor) {
        if (descriptor == null) {
            return NULL;
        }
        final Object value = switch (attribute) {
            case ID -> descriptor.endpointId();
            case SYSTEM_ID -> descriptor.systemId();
            case ORGANISATION -> descriptor.organisation();
            case URL -> descriptor.url();
        };
        return value == null ? NULL : value;
    }

    // ---- meta ----------------------------------------------------------------

    private static ResultEnvelope.Meta buildMeta(final Assembly assembly,
                                                 final List<ResultEnvelope.Dedup.Suppressed> suppressed) {
        final List<ResultEnvelope.EndpointMeta> endpointMetas = new ArrayList<>();
        // §11.1 "What in scope means" / N37: complete is true only when every
        // node that was IN SCOPE reached active. `excluded` and `not-localized`
        // were never in scope — nothing intended to ask them — so they are
        // reported but do not clear the flag. Without this, a nine-member
        // federation whose localizer names three would report complete: false
        // on every undirected query, which is the ordinary case, not a defect.
        boolean complete = true;
        for (final NodeOutcome outcome : assembly.outcomes()) {
            if (outcome.inScope() && !outcome.succeeded()) {
                complete = false;
            }
            final EndpointDescriptor d = assembly.endpointInfo().get(outcome.endpointId());
            endpointMetas.add(new ResultEnvelope.EndpointMeta(
                    outcome.endpointId(),
                    outcome.status(),
                    outcome.error(),
                    // N40/§9.5: latency_ms is the gateway's own measurement of ITS
                    // request to this endpoint, so it is reported exactly when a
                    // query was dispatched. Statuses settled before dispatch
                    // (`excluded`, `not-localized`, `not-resolved`, a pre-filtered
                    // `consent-denied`) were never timed, and NodeOutcome.skipped
                    // already carries a null latency for them — the guard makes that
                    // contract explicit rather than incidental.
                    outcome.dispatched() ? outcome.latencyMs() : null,
                    d == null ? null : d.nodeId(),
                    d == null ? null : d.systemId(),
                    d == null ? null : d.organisation(),
                    d == null ? null : d.product(),
                    d == null ? null : d.version(),
                    outcome.succeeded() ? outcome.rows().size() : null,
                    outcome.url() != null ? outcome.url() : (d == null ? null : d.url())));
        }
        final ResultEnvelope.Timeout timeout = new ResultEnvelope.Timeout(
                assembly.budget().perNode().toMillis(),
                assembly.configuredOverallMs(),
                assembly.budget().overall().toMillis());
        final ResultEnvelope.Dedup dedup = "version-identity".equals(assembly.dedupMode())
                ? new ResultEnvelope.Dedup("version-identity", suppressed)
                : null;
        return new ResultEnvelope.Meta(complete, endpointMetas, timeout, dedup);
    }
}
