// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.analysis;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.aql.analysis.SubjectAnalysis.Carrier;
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
import org.ehrbase.openehr.sdk.aql.dto.operand.LongPrimitive;
import org.ehrbase.openehr.sdk.aql.dto.operand.StringPrimitive;
import org.ehrbase.openehr.sdk.aql.dto.orderby.OrderByExpression;
import org.ehrbase.openehr.sdk.aql.dto.path.AqlObjectPath;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Locates the patient identifier in whichever carrier the client used (§5.4.3,
 * N33) and every subject projection, and rejects — fail closed, 400 — what the
 * gateway cannot consume in resolution.
 *
 * <p>Two carriers are resolution input, and both are mandatory (CP-38):
 * <ul>
 *   <li>{@code e/ehr_status/subject/external_ref/id/value = '<patientId>'}, the
 *       canonical form of §7.1, with the {@code PARTY_REF}'s {@code namespace}
 *       as the issuing namespace;</li>
 *   <li>an {@code ENTRY}-level {@code subject} — {@code PARTY_IDENTIFIED} or its
 *       {@code PARTY_RELATED} subtype — as
 *       {@code x/…/subject/identifiers/id = '<patientId>'}, with the
 *       {@code DV_IDENTIFIER}'s {@code issuer} (or {@code type}) as the issuing
 *       namespace.</li>
 * </ul>
 *
 * <p>Rejection is by <em>value</em>, not by path shape, because §5.4.1 forbids
 * the patient's identifier on the wire and says nothing about clinician or
 * facility predicates: {@code c/composer/identifiers/id = '<clinician>'} is
 * ordinary query material and is dispatched. What is still refused on path
 * alone is a patient-identifying path resolution did not consume — a second
 * {@code ENTRY}-level {@code subject} value, an {@code ORDER BY} over the
 * subject, a non-canonical {@code external_ref/id} form — and any
 * identifier-bearing predicate whose literal <em>is</em> the resolved patient
 * identifier, which is the smuggling case. Subject predicates in positions the
 * query cannot be reduced from (OR/NOT/MATCHES/LIKE) are rejected as before.
 *
 * <p>{@code PARTY_RELATED} is indistinguishable from {@code PARTY_IDENTIFIED} by
 * path, so an {@code ENTRY}-level {@code subject} over a relative is handled as
 * the patient carrier: consumed when it is the resolution input, rejected
 * otherwise. §5.4.3 leaves the third-party question open; this is the
 * conservative side of it.
 */
public final class SubjectPredicateExtractor {

    public static final String SUBJECT_PATH = "ehr_status/subject/external_ref/id/value";

    /** The {@code PARTY_REF.namespace} behind the canonical carrier (§5.4.3). */
    public static final String SUBJECT_NAMESPACE_PATH = "ehr_status/subject/external_ref/namespace";

    private static final List<String> ENTRY_SUBJECT_ID_TAIL = List.of("subject", "identifiers", "id");
    private static final String ISSUER = "issuer";
    private static final String TYPE = "type";

    private SubjectPredicateExtractor() {
    }

    public static SubjectAnalysis analyse(final AqlQuery query) {
        final ContainmentClassExpression ehr = findEhrContainment(query.getFrom());
        if (ehr == null) {
            throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED,
                    "Query must be scoped on an EHR (FROM EHR e …)");
        }

        final List<SubjectPredicate> found = new ArrayList<>();
        final Set<String> subjectValues = new LinkedHashSet<>();
        walk(query.getWhere(), true, (leaf, reducible) -> collectSubjectPredicate(
                leaf, reducible, ehr, found, subjectValues));
        if (subjectValues.size() > 1) {
            throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                    "Query contains multiple different subject identifiers and cannot be reduced "
                            + "to a single ehr_id scope per node");
        }
        final String subjectId = subjectValues.stream().findFirst().orElse(null);
        final Carrier carrier = carrierOf(found);

        final List<ComparisonOperatorCondition> namespacePredicates = new ArrayList<>();
        final Set<String> primaryNamespaces = new LinkedHashSet<>();
        final Set<String> typeNamespaces = new LinkedHashSet<>();
        walk(query.getWhere(), true, (leaf, reducible) -> collectNamespacePredicate(
                leaf, reducible, ehr, found, namespacePredicates, primaryNamespaces, typeNamespaces));
        final String issuingNamespace = issuingNamespace(primaryNamespaces, typeNamespaces);

        final List<SubjectAnalysis.SubjectProjection> projections = findSubjectProjections(query, ehr);
        if (!projections.isEmpty() && subjectId == null) {
            throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                    "Subject projection requires a subject predicate to re-inject from");
        }

        final List<ComparisonOperatorCondition> subjectPredicates =
                found.stream().map(SubjectPredicate::condition).toList();
        guardForeignIdentifierPaths(query, ehr, subjectId, subjectPredicates, namespacePredicates, projections);
        return new SubjectAnalysis(subjectId, issuingNamespace, carrier, ehr,
                subjectPredicates, namespacePredicates, projections);
    }

    /**
     * The rendered paths of every patient-carrier predicate compared to a
     * <em>literal</em> — as opposed to a {@code $parameter} — anywhere in WHERE.
     *
     * <p>For the stored-query registry (§12.7): a definition is persisted and
     * outlives the request, so a literal patient identifier in it would be a
     * patient identifier at rest, which N33 forbids the gateway to hold. A
     * definition names the patient through a parameter and the value arrives
     * per invocation. Only the carrier paths count; a literal elsewhere is
     * ordinary query material.
     */
    public static List<String> literalSubjectPaths(final AqlQuery query) {
        final ContainmentClassExpression ehr = findEhrContainment(query.getFrom());
        if (ehr == null) {
            return List.of();
        }
        final List<String> paths = new ArrayList<>();
        walk(query.getWhere(), true, (leaf, reducible) -> {
            final IdentifiedPath statement = switch (leaf) {
                case ComparisonOperatorCondition cmp
                        when identifierLiteral(cmp.getValue()) != null
                        && cmp.getStatement() instanceof IdentifiedPath ip -> ip;
                case LikeCondition like when like.getValue() instanceof StringPrimitive -> like.getStatement();
                case MatchesCondition matches
                        when Objects.requireNonNullElse(matches.getValues(), List.of()).stream()
                        .anyMatch(StringPrimitive.class::isInstance) -> matches.getStatement();
                default -> null;
            };
            if (statement != null && carrierOf(statement, ehr) != null) {
                paths.add(statement.render());
            }
        });
        return paths;
    }

    /** A subject equality and the carrier it arrived in. */
    private record SubjectPredicate(ComparisonOperatorCondition condition, Carrier carrier) {
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

    /** Visits the leaves of a WHERE tree. */
    @FunctionalInterface
    private interface LeafVisitor {
        /**
         * @param reducible true only while descending pure AND chains from the
         *                  root — a subject predicate anywhere else (OR, NOT, …)
         *                  cannot be reduced to a single scope
         */
        void visit(WhereCondition leaf, boolean reducible);
    }

    private static void walk(final WhereCondition condition, final boolean reducible, final LeafVisitor visitor) {
        switch (condition) {
            case null -> {
                // no WHERE clause
            }
            case LogicalOperatorCondition logical -> {
                final boolean isAnd = logical.getSymbol()
                        == LogicalOperatorCondition.ConditionLogicalOperatorSymbol.AND;
                for (final WhereCondition child : logical.getValues()) {
                    walk(child, reducible && isAnd, visitor);
                }
            }
            case NotCondition not -> walk(not.getConditionDto(), false, visitor);
            default -> visitor.visit(condition, reducible);
        }
    }

    private static void collectSubjectPredicate(final WhereCondition leaf,
                                                final boolean reducible,
                                                final ContainmentClassExpression ehr,
                                                final List<SubjectPredicate> found,
                                                final Set<String> values) {
        final Carrier carrier = switch (leaf) {
            case ComparisonOperatorCondition cmp -> carrierOf(cmp.getStatement(), ehr);
            case MatchesCondition matches -> carrierOf(matches.getStatement(), ehr);
            case LikeCondition like -> carrierOf(like.getStatement(), ehr);
            // EXISTS carries no comparison value; the hygiene guard still sees the path
            case ExistsCondition ignored -> null;
            default -> null;
        };
        if (carrier == null) {
            return;
        }
        final String value = leaf instanceof ComparisonOperatorCondition cmp ? identifierLiteral(cmp.getValue()) : null;
        if (!reducible
                || !(leaf instanceof ComparisonOperatorCondition cmp)
                || cmp.getSymbol() != ComparisonOperatorSymbol.EQ
                || value == null) {
            throw unreducible();
        }
        found.add(new SubjectPredicate(cmp, carrier));
        values.add(value);
    }

    /**
     * The identifier a comparison names: a string, or an integer taken as its
     * decimal digits. The second form arrives through {@code query_parameters}
     * — a JSON {@code 12345} or a {@code GET ?patient_id=12345} — and the
     * identifier <em>is</em> those digits; resolution consumes it, so no node
     * ever sees the operand's type. Anything else is not an identifier literal.
     */
    private static String identifierLiteral(final Object operand) {
        return switch (operand) {
            case StringPrimitive sp -> sp.getValue();
            case LongPrimitive lp when lp.getValue() != null -> Long.toString(lp.getValue());
            case null, default -> null;
        };
    }

    /**
     * The carrier a path names the patient through, or null when it is not a
     * resolution input. The canonical form is exact and EHR-rooted; the
     * {@code ENTRY}-level form is any non-EHR-rooted path ending in
     * {@code subject/identifiers/id} — {@code o/subject/identifiers/id} or
     * {@code c/content[…]/subject/identifiers/id} alike.
     */
    private static Carrier carrierOf(final Object operand, final ContainmentClassExpression ehr) {
        if (!(operand instanceof IdentifiedPath ip) || ip.getPath() == null) {
            return null;
        }
        if (ip.getRoot() == ehr) {
            return SUBJECT_PATH.equals(ip.getPath().render()) ? Carrier.EXTERNAL_REF : null;
        }
        return endsWith(attributes(ip.getPath()), ENTRY_SUBJECT_ID_TAIL) ? Carrier.ENTRY_SUBJECT : null;
    }

    private static boolean isSubjectPath(final Object operand, final ContainmentClassExpression ehr) {
        return carrierOf(operand, ehr) != null;
    }

    /** The canonical carrier when present; a query naming the patient through both is still one subject. */
    private static Carrier carrierOf(final List<SubjectPredicate> found) {
        if (found.isEmpty()) {
            return null;
        }
        return found.stream().anyMatch(p -> p.carrier() == Carrier.EXTERNAL_REF)
                ? Carrier.EXTERNAL_REF
                : Carrier.ENTRY_SUBJECT;
    }

    // ---- namespace (§5.2, §5.4.3) --------------------------------------------

    /**
     * A namespace predicate is the sibling of a consumed subject predicate: the
     * same root, and {@code external_ref/namespace} beside
     * {@code external_ref/id/value}, or {@code identifiers/issuer} /
     * {@code identifiers/type} beside {@code identifiers/id}. It is consumed
     * under the same reducibility rule as the identifier, and rejected in any
     * form resolution cannot read a single value from.
     */
    private static void collectNamespacePredicate(final WhereCondition leaf,
                                                  final boolean reducible,
                                                  final ContainmentClassExpression ehr,
                                                  final List<SubjectPredicate> subjects,
                                                  final List<ComparisonOperatorCondition> namespacePredicates,
                                                  final Set<String> primaryNamespaces,
                                                  final Set<String> typeNamespaces) {
        final IdentifiedPath ip = switch (leaf) {
            case ComparisonOperatorCondition cmp
                    when cmp.getStatement() instanceof IdentifiedPath path -> path;
            case MatchesCondition matches -> matches.getStatement();
            case LikeCondition like -> like.getStatement();
            default -> null;
        };
        if (ip == null || ip.getPath() == null) {
            return;
        }
        final String kind = namespaceKind(ip, ehr, subjects);
        if (kind == null) {
            return;
        }
        final String value = leaf instanceof ComparisonOperatorCondition cmp ? identifierLiteral(cmp.getValue()) : null;
        if (!reducible
                || !(leaf instanceof ComparisonOperatorCondition cmp)
                || cmp.getSymbol() != ComparisonOperatorSymbol.EQ
                || value == null) {
            throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                    "Subject identifier namespace cannot be consumed in resolution; only a "
                            + "top-level AND-combined equality beside the subject identifier is supported");
        }
        namespacePredicates.add(cmp);
        (TYPE.equals(kind) ? typeNamespaces : primaryNamespaces).add(value);
    }

    /**
     * {@code "namespace"}, {@code "issuer"} or {@code "type"} when {@code ip} is
     * the namespace sibling of a consumed subject predicate; null otherwise.
     */
    private static String namespaceKind(final IdentifiedPath ip, final ContainmentClassExpression ehr,
                                        final List<SubjectPredicate> subjects) {
        for (final SubjectPredicate subject : subjects) {
            final IdentifiedPath subjectPath = (IdentifiedPath) subject.condition().getStatement();
            if (ip.getRoot() != subjectPath.getRoot()) {
                continue;
            }
            if (subject.carrier() == Carrier.EXTERNAL_REF) {
                if (ip.getRoot() == ehr && SUBJECT_NAMESPACE_PATH.equals(ip.getPath().render())) {
                    return "namespace";
                }
                continue;
            }
            final List<AqlObjectPath.PathNode> idNodes = subjectPath.getPath().getPathNodes();
            final List<AqlObjectPath.PathNode> nodes = ip.getPath().getPathNodes();
            if (nodes.size() != idNodes.size()
                    || !nodes.subList(0, nodes.size() - 1).equals(idNodes.subList(0, idNodes.size() - 1))) {
                continue;
            }
            final String last = nodes.get(nodes.size() - 1).getAttribute();
            if (ISSUER.equals(last) || TYPE.equals(last)) {
                return last;
            }
        }
        return null;
    }

    /**
     * {@code external_ref/namespace} and {@code identifiers/issuer} are the
     * namespace proper; {@code identifiers/type} is the fallback §5.4.3 allows
     * when no issuer is given. Two different values for the same patient is a
     * query resolution cannot satisfy.
     */
    private static String issuingNamespace(final Set<String> primary, final Set<String> type) {
        if (primary.size() > 1 || (primary.isEmpty() && type.size() > 1)) {
            throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                    "Query names more than one issuing namespace for the subject identifier");
        }
        return primary.stream().findFirst()
                .or(() -> type.stream().findFirst())
                .orElse(null);
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

    /** A path as used somewhere in the query, with the literal it is compared to, if any. */
    private record PathUse(IdentifiedPath path, List<String> literals) {
    }

    private static void guardForeignIdentifierPaths(final AqlQuery query,
                                                    final ContainmentClassExpression ehr,
                                                    final String subjectId,
                                                    final List<ComparisonOperatorCondition> subjectPredicates,
                                                    final List<ComparisonOperatorCondition> namespacePredicates,
                                                    final List<SubjectAnalysis.SubjectProjection> subjectProjections) {
        final List<PathUse> all = new ArrayList<>();
        for (final SelectExpression expr : query.getSelect().getStatement()) {
            if (expr.getColumnExpression() instanceof IdentifiedPath ip) {
                all.add(new PathUse(ip, List.of()));
            }
        }
        collectPathUses(query.getWhere(), all);
        for (final OrderByExpression order : Objects.requireNonNullElse(
                query.getOrderBy(), List.<OrderByExpression>of())) {
            all.add(new PathUse(order.getStatement(), List.of()));
        }

        // Identity semantics: only the exact AST nodes consumed by resolution are
        // permitted — a value-equal path elsewhere (e.g. ORDER BY) must still fail.
        final Set<IdentifiedPath> permitted = Collections.newSetFromMap(new IdentityHashMap<>());
        subjectPredicates.forEach(p -> permitted.add((IdentifiedPath) p.getStatement()));
        namespacePredicates.forEach(p -> permitted.add((IdentifiedPath) p.getStatement()));
        subjectProjections.forEach(p -> permitted.add((IdentifiedPath) p.expression().getColumnExpression()));

        for (final PathUse use : all) {
            final IdentifiedPath ip = use.path();
            if (ip == null || permitted.contains(ip) || ip.getPath() == null) {
                continue;
            }
            final String path = ip.getPath().render();
            final List<String> attributes = attributes(ip.getPath());

            // Patient-identifying by construction, and not consumed: an
            // ENTRY-level subject (PARTY_IDENTIFIED / PARTY_RELATED, any
            // attribute of it) or a non-canonical use of the canonical carrier.
            final boolean patientCarrier = (ip.getRoot() != ehr && attributes.contains("subject"))
                    || (ip.getRoot() == ehr && path.contains("external_ref/id"));
            if (patientCarrier) {
                throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                        "Path '" + path + "' identifies the patient and was not consumed in "
                                + "resolution; remove it or resolve via it (N33, §5.4.3)");
            }

            // Every other identifier-bearing path — composer, performer,
            // health_care_facility, committer — names a clinician, facility or
            // third party and is dispatched, unless its literal is the patient
            // identifier itself: the smuggling case §5.4.2 lists them for.
            final boolean identifierBearing = attributes.contains("identifiers")
                    || attributes.contains("external_ref");
            if (identifierBearing && subjectId != null && use.literals().contains(subjectId)) {
                throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                        "Path '" + path + "' is compared to the patient identifier the query "
                                + "resolves on; a node must be located by ehr_id alone (N33)");
            }
        }
    }

    private static void collectPathUses(final WhereCondition condition, final List<PathUse> sink) {
        switch (condition) {
            case null -> {
                // no WHERE clause
            }
            case ComparisonOperatorCondition cmp -> {
                final List<String> literals = new ArrayList<>();
                final String literal = identifierLiteral(cmp.getValue());
                if (literal != null) {
                    literals.add(literal);
                }
                if (cmp.getStatement() instanceof IdentifiedPath ip) {
                    sink.add(new PathUse(ip, literals));
                }
                if (cmp.getValue() instanceof IdentifiedPath ip) {
                    sink.add(new PathUse(ip, literals));
                }
            }
            case LogicalOperatorCondition logical -> logical.getValues().forEach(c -> collectPathUses(c, sink));
            case NotCondition not -> collectPathUses(not.getConditionDto(), sink);
            case MatchesCondition matches -> {
                final List<String> literals = new ArrayList<>();
                for (final Object value : Objects.requireNonNullElse(matches.getValues(), List.of())) {
                    if (value instanceof StringPrimitive sp) {
                        literals.add(sp.getValue());
                    }
                }
                sink.add(new PathUse(matches.getStatement(), literals));
            }
            case LikeCondition like -> {
                final List<String> literals = like.getValue() instanceof StringPrimitive sp
                        ? List.of(sp.getValue())
                        : List.of();
                sink.add(new PathUse(like.getStatement(), literals));
            }
            case ExistsCondition exists -> sink.add(new PathUse(exists.getValue(), List.of()));
            default -> {
            }
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static List<String> attributes(final AqlObjectPath path) {
        return path.getPathNodes().stream().map(AqlObjectPath.PathNode::getAttribute).toList();
    }

    private static boolean endsWith(final List<String> attributes, final List<String> tail) {
        return attributes.size() >= tail.size()
                && attributes.subList(attributes.size() - tail.size(), attributes.size()).equals(tail);
    }

    private static FederationException unreducible() {
        return new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                "Subject predicate cannot be reduced to a single ehr_id scope per node; "
                        + "only a top-level AND-combined equality on "
                        + "ehr_status/subject/external_ref/id/value or an ENTRY-level "
                        + "subject/identifiers/id is supported");
    }
}
