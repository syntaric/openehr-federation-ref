// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.analysis;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.dto.condition.ComparisonOperatorCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.ComparisonOperatorSymbol;
import org.ehrbase.openehr.sdk.aql.dto.condition.ExistsCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.LikeCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.LogicalOperatorCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.MatchesCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.NotCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.WhereCondition;
import org.ehrbase.openehr.sdk.aql.dto.containment.AbstractContainmentExpression;
import org.ehrbase.openehr.sdk.aql.dto.containment.Containment;
import org.ehrbase.openehr.sdk.aql.dto.containment.ContainmentClassExpression;
import org.ehrbase.openehr.sdk.aql.dto.containment.ContainmentNotOperator;
import org.ehrbase.openehr.sdk.aql.dto.containment.ContainmentSetOperator;
import org.ehrbase.openehr.sdk.aql.dto.operand.IdentifiedPath;
import org.ehrbase.openehr.sdk.aql.dto.operand.StringPrimitive;
import org.ehrbase.openehr.sdk.aql.dto.orderby.OrderByExpression;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Locates the canonical subject predicate
 * {@code e/ehr_status/subject/external_ref/id/value = '<patientId>'} (spec §7.1)
 * and every subject projection, and rejects — fail closed, 400 — any other
 * identifier-bearing path the gateway cannot consume in resolution (N33):
 * {@code PARTY_IDENTIFIED.identifiers} / {@code DV_IDENTIFIER} paths,
 * {@code external_ref/id} paths outside the canonical form, in WHERE, SELECT or
 * ORDER BY, and subject predicates the query cannot be reduced from (OR/NOT/
 * MATCHES/LIKE positions).
 */
public final class SubjectPredicateExtractor {

    public static final String SUBJECT_PATH = "ehr_status/subject/external_ref/id/value";

    private SubjectPredicateExtractor() {
    }

    public static SubjectAnalysis analyse(final AqlQuery query) {
        final ContainmentClassExpression ehr = findEhrContainment(query.getFrom());
        if (ehr == null) {
            throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED,
                    "Query must be scoped on an EHR (FROM EHR e …)");
        }

        final List<ComparisonOperatorCondition> subjectPredicates = new ArrayList<>();
        final Set<String> subjectValues = new LinkedHashSet<>();
        if (query.getWhere() != null) {
            collectSubjectPredicates(query.getWhere(), ehr, true, subjectPredicates, subjectValues);
        }
        if (subjectValues.size() > 1) {
            throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                    "Query contains multiple different subject identifiers and cannot be reduced "
                            + "to a single ehr_id scope per node");
        }
        final String subjectId = subjectValues.stream().findFirst().orElse(null);

        final List<SubjectAnalysis.SubjectProjection> projections = findSubjectProjections(query, ehr);
        if (!projections.isEmpty() && subjectId == null) {
            throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                    "Subject projection requires a subject predicate to re-inject from");
        }

        guardForeignIdentifierPaths(query, ehr, subjectPredicates, projections);
        return new SubjectAnalysis(subjectId, ehr, subjectPredicates, projections);
    }

    // ---- containment ---------------------------------------------------------

    private static ContainmentClassExpression findEhrContainment(final Containment containment) {
        if (containment == null) {
            return null;
        }
        if (containment instanceof ContainmentClassExpression cce) {
            if ("EHR".equalsIgnoreCase(cce.getType())) {
                return cce;
            }
            return findEhrContainment(cce.getContains());
        }
        if (containment instanceof AbstractContainmentExpression ace) {
            return findEhrContainment(ace.getContains());
        }
        if (containment instanceof ContainmentSetOperator cso) {
            for (final Containment child : cso.getValues()) {
                final ContainmentClassExpression found = findEhrContainment(child);
                if (found != null) {
                    return found;
                }
            }
        }
        if (containment instanceof ContainmentNotOperator) {
            return null;
        }
        return null;
    }

    // ---- WHERE ---------------------------------------------------------------

    /**
     * Collects canonical subject predicates. {@code reducible} is true only while
     * descending pure AND chains from the root — a subject predicate anywhere
     * else (OR, NOT, …) cannot be reduced to a single scope and is rejected.
     */
    private static void collectSubjectPredicates(final WhereCondition condition,
                                                 final ContainmentClassExpression ehr,
                                                 final boolean reducible,
                                                 final List<ComparisonOperatorCondition> found,
                                                 final Set<String> values) {
        switch (condition) {
            case ComparisonOperatorCondition cmp -> {
                if (isSubjectPath(cmp.getStatement(), ehr)) {
                    if (!reducible) {
                        throw unreducible();
                    }
                    if (cmp.getSymbol() != ComparisonOperatorSymbol.EQ
                            || !(cmp.getValue() instanceof StringPrimitive sp)) {
                        throw unreducible();
                    }
                    found.add(cmp);
                    values.add(sp.getValue());
                }
            }
            case LogicalOperatorCondition logical -> {
                final boolean isAnd = logical.getSymbol()
                        == LogicalOperatorCondition.ConditionLogicalOperatorSymbol.AND;
                for (final WhereCondition child : logical.getValues()) {
                    collectSubjectPredicates(child, ehr, reducible && isAnd, found, values);
                }
            }
            case NotCondition not -> collectSubjectPredicates(not.getConditionDto(), ehr, false, found, values);
            case MatchesCondition matches -> {
                if (isSubjectPath(matches.getStatement(), ehr)) {
                    throw unreducible();
                }
            }
            case LikeCondition like -> {
                if (isSubjectPath(like.getStatement(), ehr)) {
                    throw unreducible();
                }
            }
            case ExistsCondition ignored -> {
                // EXISTS carries no comparison value
            }
            default -> {
                // unknown condition type: nothing to collect; hygiene guard still applies
            }
        }
    }

    private static boolean isSubjectPath(final Object operand, final ContainmentClassExpression ehr) {
        return operand instanceof IdentifiedPath ip
                && ip.getRoot() == ehr
                && ip.getPath() != null
                && SUBJECT_PATH.equals(ip.getPath().render());
    }

    // ---- SELECT --------------------------------------------------------------

    private static List<SubjectAnalysis.SubjectProjection> findSubjectProjections(
            final AqlQuery query, final ContainmentClassExpression ehr) {
        final List<SubjectAnalysis.SubjectProjection> projections = new ArrayList<>();
        final List<SelectExpression> select = query.getSelect().getStatement();
        for (int i = 0; i < select.size(); i++) {
            final SelectExpression expr = select.get(i);
            if (expr.getColumnExpression() instanceof IdentifiedPath ip && isSubjectPath(ip, ehr)) {
                final String name = expr.getAlias() != null ? expr.getAlias() : ip.render();
                projections.add(new SubjectAnalysis.SubjectProjection(expr, name, i));
            }
        }
        return projections;
    }

    // ---- N33 guard -----------------------------------------------------------

    private static void guardForeignIdentifierPaths(final AqlQuery query,
                                                    final ContainmentClassExpression ehr,
                                                    final List<ComparisonOperatorCondition> subjectPredicates,
                                                    final List<SubjectAnalysis.SubjectProjection> subjectProjections) {
        final List<IdentifiedPath> all = new ArrayList<>();
        for (final SelectExpression expr : query.getSelect().getStatement()) {
            if (expr.getColumnExpression() instanceof IdentifiedPath ip) {
                all.add(ip);
            }
        }
        if (query.getWhere() != null) {
            collectPaths(query.getWhere(), all);
        }
        for (final OrderByExpression order : Objects.requireNonNullElse(
                query.getOrderBy(), List.<OrderByExpression>of())) {
            all.add(order.getStatement());
        }

        // Identity semantics: only the exact AST nodes consumed by resolution are
        // permitted — a value-equal path elsewhere (e.g. ORDER BY) must still fail.
        final Set<IdentifiedPath> permitted =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        subjectPredicates.forEach(p -> permitted.add((IdentifiedPath) p.getStatement()));
        subjectProjections.forEach(p -> permitted.add((IdentifiedPath) p.expression().getColumnExpression()));

        for (final IdentifiedPath ip : all) {
            if (permitted.contains(ip) || ip.getPath() == null) {
                continue;
            }
            final String path = ip.getPath().render();
            final boolean identifierBearing = path.contains("identifiers")
                    || path.contains("external_ref/id")
                    || (ip.getRoot() != ehr && path.contains("subject/"));
            if (identifierBearing) {
                throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                        "Path '" + path + "' may carry a directly identifying identifier and cannot be "
                                + "consumed in resolution; remove it or resolve via "
                                + "ehr_status/subject/external_ref/id/value (N33)");
            }
        }
    }

    private static void collectPaths(final WhereCondition condition, final List<IdentifiedPath> sink) {
        switch (condition) {
            case ComparisonOperatorCondition cmp -> {
                if (cmp.getStatement() instanceof IdentifiedPath ip) {
                    sink.add(ip);
                }
                if (cmp.getValue() instanceof IdentifiedPath ip) {
                    sink.add(ip);
                }
            }
            case LogicalOperatorCondition logical -> logical.getValues().forEach(c -> collectPaths(c, sink));
            case NotCondition not -> collectPaths(not.getConditionDto(), sink);
            case MatchesCondition matches -> sink.add(matches.getStatement());
            case LikeCondition like -> sink.add(like.getStatement());
            case ExistsCondition exists -> sink.add(exists.getValue());
            default -> {
            }
        }
    }

    private static FederationException unreducible() {
        return new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                "Subject predicate cannot be reduced to a single ehr_id scope per node; "
                        + "only a top-level AND-combined equality on "
                        + "ehr_status/subject/external_ref/id/value is supported");
    }
}
