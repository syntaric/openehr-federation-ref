// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.parameters;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.aql.rewrite.AqlRewriter;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.dto.condition.ComparisonOperatorCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.ExistsCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.LikeCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.LogicalOperatorCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.MatchesCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.NotCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.WhereCondition;
import org.ehrbase.openehr.sdk.aql.dto.containment.AbstractContainmentExpression;
import org.ehrbase.openehr.sdk.aql.dto.containment.Containment;
import org.ehrbase.openehr.sdk.aql.dto.containment.ContainmentNotOperator;
import org.ehrbase.openehr.sdk.aql.dto.containment.ContainmentSetOperator;
import org.ehrbase.openehr.sdk.aql.dto.containment.ContainmentVersionExpression;
import org.ehrbase.openehr.sdk.aql.dto.operand.AggregateFunction;
import org.ehrbase.openehr.sdk.aql.dto.operand.BooleanPrimitive;
import org.ehrbase.openehr.sdk.aql.dto.operand.DoublePrimitive;
import org.ehrbase.openehr.sdk.aql.dto.operand.IdentifiedPath;
import org.ehrbase.openehr.sdk.aql.dto.operand.LikeOperand;
import org.ehrbase.openehr.sdk.aql.dto.operand.LongPrimitive;
import org.ehrbase.openehr.sdk.aql.dto.operand.MatchesOperand;
import org.ehrbase.openehr.sdk.aql.dto.operand.Operand;
import org.ehrbase.openehr.sdk.aql.dto.operand.PathPredicateOperand;
import org.ehrbase.openehr.sdk.aql.dto.operand.Primitive;
import org.ehrbase.openehr.sdk.aql.dto.operand.QueryParameter;
import org.ehrbase.openehr.sdk.aql.dto.operand.SingleRowFunction;
import org.ehrbase.openehr.sdk.aql.dto.operand.StringPrimitive;
import org.ehrbase.openehr.sdk.aql.dto.orderby.OrderByExpression;
import org.ehrbase.openehr.sdk.aql.dto.path.AndOperatorPredicate;
import org.ehrbase.openehr.sdk.aql.dto.path.AqlObjectPath;
import org.ehrbase.openehr.sdk.aql.dto.path.ComparisonOperatorPredicate;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Binds ITS-REST {@code query_parameters} into the parsed AQL <em>before</em>
 * subject analysis, so that {@code WHERE e/ehr_status/subject/external_ref/id/value
 * = $patient_id} is seen as the subject predicate it is and rewritten to an
 * {@code ehr_id} scope like any literal (§5, §12.7).
 *
 * <p>Every {@link QueryParameter} the SDK parser produced is replaced by a
 * primitive of the value's type; the SDK renderer then escapes it, so the
 * gateway never splices text into AQL. A parameter is rejected — 400, naming
 * the parameter and never the value — when it is unbound, unknown, null, a
 * container, the wrong type for its position (a number after {@code LIKE}), or
 * a string carrying the reserved {@code ehr_id} placeholder.
 *
 * <p>Pure: it mutates the AST it is given and touches nothing else.
 */
public final class QueryParameterBinder {

    private QueryParameterBinder() {
    }

    /** A {@code $name} occurrence, with how to replace it and what the position accepts. */
    private record Site(QueryParameter parameter, Position position, Consumer<Primitive<?, ?>> replace) {
    }

    private enum Position {
        /** A comparison value, function operand or MATCHES member: any primitive. */
        VALUE,
        /** After {@code LIKE}: a string pattern, nothing else. */
        LIKE,
        /** Inside a {@code [...]} path or containment predicate: string or number. */
        PATH_PREDICATE
    }

    /**
     * Replaces every {@code $name} in {@code query} with {@code parameters[name]}.
     *
     * @return the names that were bound, in first-seen order
     */
    public static Set<String> bind(final AqlQuery query, final Map<String, Object> parameters) {
        final List<Site> sites = new ArrayList<>();
        collect(query, sites);

        final Set<String> named = new LinkedHashSet<>();
        sites.forEach(s -> named.add(s.parameter().getName()));

        final Set<String> unbound = new TreeSet<>(named);
        unbound.removeAll(parameters.keySet());
        if (!unbound.isEmpty()) {
            throw invalid("Query names parameters the request does not supply",
                    Map.of("unbound", List.copyOf(unbound)));
        }
        final Set<String> unknown = new TreeSet<>(parameters.keySet());
        unknown.removeAll(named);
        if (!unknown.isEmpty()) {
            throw invalid("Request supplies parameters the query does not name",
                    Map.of("unknown", List.copyOf(unknown)));
        }

        for (final Site site : sites) {
            site.replace().accept(toPrimitive(site.parameter().getName(),
                    parameters.get(site.parameter().getName()), site.position()));
        }
        return named;
    }

    /**
     * The string-level backstop after rendering: a {@code $ident} outside a
     * quoted literal means a parameter survived binding — a position this
     * walker does not know about — and the query MUST NOT be dispatched with it.
     * Quote-aware because the rewriter's own placeholder is {@code $$…$$}
     * <em>inside</em> quotes and is not a parameter.
     */
    public static void assertNoUnboundParameter(final String renderedAql) {
        char quote = 0;
        for (int i = 0; i < renderedAql.length(); i++) {
            final char c = renderedAql.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '$' && i + 1 < renderedAql.length()
                    && isIdentifierStart(renderedAql.charAt(i + 1))) {
                throw invalid("Query still carries an unbound $parameter after binding", Map.of());
            }
        }
    }

    private static boolean isIdentifierStart(final char c) {
        return Character.isLetter(c) || c == '_';
    }

    // ---- value conversion ----------------------------------------------------

    private static Primitive<?, ?> toPrimitive(final String name, final Object value, final Position position) {
        final Primitive<?, ?> primitive = switch (value) {
            case null -> throw invalid("Parameter value is null", Map.of("parameter", name));
            case String s -> {
                if (s.contains(AqlRewriter.EHR_ID_PLACEHOLDER)) {
                    throw invalid("Parameter value contains the reserved placeholder literal",
                            Map.of("parameter", name));
                }
                yield new StringPrimitive(s);
            }
            case Boolean b -> new BooleanPrimitive(b);
            case Integer i -> new LongPrimitive(i.longValue());
            case Long l -> new LongPrimitive(l);
            case Short s -> new LongPrimitive(s.longValue());
            case Byte b -> new LongPrimitive(b.longValue());
            case BigInteger b -> new LongPrimitive(b.longValueExact());
            case Double d -> new DoublePrimitive(d);
            case Float f -> new DoublePrimitive(f.doubleValue());
            case BigDecimal d -> new DoublePrimitive(d.doubleValue());
            default -> throw invalid("Parameter value must be a string, number or boolean",
                    Map.of("parameter", name));
        };
        if (position == Position.LIKE && !(primitive instanceof StringPrimitive)) {
            throw invalid("A LIKE pattern parameter must be a string", Map.of("parameter", name));
        }
        if (position == Position.PATH_PREDICATE && primitive instanceof BooleanPrimitive) {
            throw invalid("A path predicate parameter must be a string or a number", Map.of("parameter", name));
        }
        return primitive;
    }

    /**
     * A query-string value has no type; infer the narrowest AQL primitive it
     * reads as, else keep it a string. {@code "123"} is a number and
     * {@code "true"} a boolean, which is what a {@code GET} caller means by them.
     *
     * <p>Only a value whose canonical rendering round-trips is inferred:
     * {@code "0123"} stays a string, because a number would drop the leading
     * zero — and an identifier with one is exactly the value a caller cannot
     * afford to have silently rewritten.
     */
    public static Object inferFromString(final String raw) {
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        try {
            final long asLong = Long.parseLong(raw);
            if (Long.toString(asLong).equals(raw)) {
                return asLong;
            }
        } catch (final NumberFormatException ignored) {
            // not a long
        }
        try {
            if (raw.matches("-?\\d+\\.\\d+")) {
                final double asDouble = Double.parseDouble(raw);
                if (Double.toString(asDouble).equals(raw)) {
                    return asDouble;
                }
            }
        } catch (final NumberFormatException ignored) {
            // not a double
        }
        return raw;
    }

    // ---- AST walk ------------------------------------------------------------

    private static void collect(final AqlQuery query, final List<Site> sites) {
        for (final SelectExpression select : query.getSelect().getStatement()) {
            collectColumn(select.getColumnExpression(), sites);
        }
        collectContainment(query.getFrom(), sites);
        collectWhere(query.getWhere(), sites);
        for (final OrderByExpression order : Objects.requireNonNullElse(
                query.getOrderBy(), List.<OrderByExpression>of())) {
            collectPath(order.getStatement(), sites);
        }
    }

    private static void collectColumn(final Object column, final List<Site> sites) {
        switch (column) {
            case IdentifiedPath path -> collectPath(path, sites);
            case AggregateFunction aggregate -> collectPath(aggregate.getIdentifiedPath(), sites);
            case SingleRowFunction function -> collectFunction(function, sites);
            case null, default -> {
                // a literal column: nothing to bind
            }
        }
    }

    private static void collectFunction(final SingleRowFunction function, final List<Site> sites) {
        final List<Operand> operands = function.getOperandList();
        if (operands == null) {
            return;
        }
        for (int i = 0; i < operands.size(); i++) {
            final int index = i;
            collectOperand(operands.get(i), Position.VALUE, p -> operands.set(index, p), sites);
        }
    }

    private static void collectOperand(final Object operand, final Position position,
                                       final Consumer<Primitive<?, ?>> replace, final List<Site> sites) {
        switch (operand) {
            case QueryParameter parameter -> sites.add(new Site(parameter, position, replace));
            case IdentifiedPath path -> collectPath(path, sites);
            case SingleRowFunction function -> collectFunction(function, sites);
            case null, default -> {
                // a literal: nothing to bind
            }
        }
    }

    private static void collectWhere(final WhereCondition condition, final List<Site> sites) {
        switch (condition) {
            case null -> {
                // no WHERE clause
            }
            case LogicalOperatorCondition logical -> logical.getValues().forEach(c -> collectWhere(c, sites));
            case NotCondition not -> collectWhere(not.getConditionDto(), sites);
            case ComparisonOperatorCondition cmp -> {
                collectOperand(cmp.getStatement(), Position.VALUE, p -> { }, sites);
                collectOperand(cmp.getValue(), Position.VALUE, cmp::setValue, sites);
            }
            case LikeCondition like -> {
                collectPath(like.getStatement(), sites);
                if (like.getValue() instanceof QueryParameter parameter) {
                    sites.add(new Site(parameter, Position.LIKE, p -> like.setValue((LikeOperand) p)));
                }
            }
            case MatchesCondition matches -> {
                collectPath(matches.getStatement(), sites);
                final List<MatchesOperand> values = matches.getValues();
                if (values == null) {
                    return;
                }
                for (int i = 0; i < values.size(); i++) {
                    final int index = i;
                    if (values.get(i) instanceof QueryParameter parameter) {
                        sites.add(new Site(parameter, Position.VALUE, p -> values.set(index, p)));
                    }
                }
            }
            case ExistsCondition exists -> collectPath(exists.getValue(), sites);
            default -> {
                // a condition shape without operands
            }
        }
    }

    private static void collectContainment(final Containment containment, final List<Site> sites) {
        switch (containment) {
            case null -> {
                // no FROM (the parser would have refused it anyway)
            }
            case ContainmentVersionExpression version -> {
                collectPredicate(version.getPredicate(), sites);
                collectContainment(version.getContains(), sites);
            }
            case AbstractContainmentExpression expression -> {
                collectPredicates(expression.getPredicates(), sites);
                collectContainment(expression.getContains(), sites);
            }
            case ContainmentSetOperator set -> set.getValues().forEach(c -> collectContainment(c, sites));
            case ContainmentNotOperator not -> collectContainment(not.getContainmentExpression(), sites);
            default -> {
                // a containment shape without predicates
            }
        }
    }

    private static void collectPath(final IdentifiedPath path, final List<Site> sites) {
        if (path == null) {
            return;
        }
        collectPredicates(path.getRootPredicate(), sites);
        if (path.getPath() != null) {
            for (final AqlObjectPath.PathNode node : path.getPath().getPathNodes()) {
                collectPredicates(node.getPredicateOrOperands(), sites);
            }
        }
    }

    private static void collectPredicates(final List<AndOperatorPredicate> predicates, final List<Site> sites) {
        if (predicates == null) {
            return;
        }
        for (final AndOperatorPredicate and : predicates) {
            for (final ComparisonOperatorPredicate predicate : and.getOperands()) {
                collectPredicate(predicate, sites);
            }
        }
    }

    private static void collectPredicate(final ComparisonOperatorPredicate predicate, final List<Site> sites) {
        if (predicate != null && predicate.getValue() instanceof QueryParameter parameter) {
            sites.add(new Site(parameter, Position.PATH_PREDICATE,
                    p -> predicate.setValue((PathPredicateOperand<?>) p)));
        }
    }

    private static FederationException invalid(final String message, final Map<String, Object> details) {
        return new FederationException(FedErrorCode.FED_QUERY_PARAMETER_INVALID, message, details);
    }
}
