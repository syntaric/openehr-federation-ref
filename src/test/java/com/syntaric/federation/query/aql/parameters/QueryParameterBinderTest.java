// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.parameters;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.aql.analysis.SubjectAnalysis;
import com.syntaric.federation.query.aql.analysis.SubjectPredicateExtractor;
import com.syntaric.federation.query.aql.rewrite.AqlRewriter;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.parser.AqlQueryParser;
import org.ehrbase.openehr.sdk.aql.render.AqlRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code query_parameters} are bound into the AST, never spliced into text:
 * every position the SDK can hold a {@code $name} in, the escaping the renderer
 * applies, the typing, and the two refusals that matter for hygiene — a value
 * carrying the rewriter's placeholder, and a bound {@code $patient_id} being
 * seen as the subject it is (§12.7, N33).
 */
@Tag("CP-40")
class QueryParameterBinderTest {

    private static final String FROM = "FROM EHR e CONTAINS COMPOSITION c CONTAINS OBSERVATION o ";
    private static final String EHR_ID = "550e8400-e29b-41d4-a716-446655440000";

    private static String bind(final String aql, final Map<String, Object> parameters) {
        final AqlQuery query = AqlQueryParser.parse(aql);
        QueryParameterBinder.bind(query, parameters);
        return AqlRenderer.render(query);
    }

    private static void assertInvalid(final String aql, final Map<String, Object> parameters,
                                      final String detailKey, final String... detailValues) {
        assertThatThrownBy(() -> bind(aql, parameters))
                .isInstanceOfSatisfying(FederationException.class, e -> {
                    assertThat(e.code()).isEqualTo(FedErrorCode.FED_QUERY_PARAMETER_INVALID);
                    if (detailKey != null) {
                        assertThat(e.details().get(detailKey).toString()).contains(detailValues);
                    }
                });
    }

    // ---- every renderer site --------------------------------------------------

    @Test
    void bindsAComparisonValue() {
        assertThat(bind("SELECT c/uid/value " + FROM + "WHERE c/name/value = $nm", Map.of("nm", "Encounter")))
                .contains("c/name/value = 'Encounter'");
    }

    @Test
    void bindsALikePattern() {
        assertThat(bind("SELECT c/uid/value " + FROM + "WHERE c/name/value LIKE $pat", Map.of("pat", "Enc%")))
                .contains("c/name/value LIKE 'Enc%'");
    }

    @Test
    void bindsAMatchesMember() {
        assertThat(bind("SELECT c/uid/value " + FROM + "WHERE c/archetype_node_id MATCHES {$a, 'x'}",
                Map.of("a", "openEHR-EHR-COMPOSITION.encounter.v1")))
                .contains("MATCHES {'openEHR-EHR-COMPOSITION.encounter.v1', 'x'}");
    }

    @Test
    void bindsASelectFunctionOperand() {
        assertThat(bind("SELECT c/uid/value, CONCAT(c/name/value, $suffix) AS n " + FROM, Map.of("suffix", "!")))
                .contains("CONCAT(c/name/value, '!')");
    }

    @Test
    void bindsAContainmentPredicate() {
        assertThat(bind("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c[name/value=$nm]",
                Map.of("nm", "Encounter")))
                .contains("COMPOSITION c[name/value='Encounter']");
    }

    @Test
    void bindsAPathPredicateInWhereAndSelect() {
        final String rendered = bind("SELECT c/content[name/value=$sel]/uid/value AS u " + FROM
                        + "WHERE c/content[name/value=$whr]/name/value = 'x'",
                Map.of("sel", "S", "whr", "W"));
        assertThat(rendered).contains("'S'").contains("'W'").doesNotContain("$");
    }

    @Test
    void bindsAnOrderByPathPredicate() {
        assertThat(bind("SELECT c/uid/value " + FROM + "ORDER BY c/content[name/value=$o]/name/value",
                Map.of("o", "Ordered")))
                .contains("[name/value='Ordered']");
    }

    @Test
    void bindsUnderNotAndOr() {
        final String rendered = bind("SELECT c/uid/value " + FROM
                        + "WHERE NOT (c/name/value = $a OR c/name/value = $b)", Map.of("a", "A", "b", "B"));
        assertThat(rendered).contains("'A'").contains("'B'").doesNotContain("$");
    }

    // ---- escaping and typing --------------------------------------------------

    @Test
    @DisplayName("a value with a quote is escaped by the renderer, not by string surgery")
    void quotesAreEscaped() {
        assertThat(bind("SELECT c/uid/value " + FROM + "WHERE c/name/value = $nm", Map.of("nm", "it's")))
                .contains("= 'it\\'s'");
    }

    @Test
    void numbersAndBooleansStayUnquoted() {
        final String rendered = bind("SELECT c/uid/value " + FROM
                        + "WHERE o/data/events/data/items/value/magnitude > $lo "
                        + "AND o/data/events/data/items/value/magnitude < $hi "
                        + "AND c/context/other_context/items/value/value = $flag",
                Map.of("lo", 5, "hi", 7.5, "flag", true));
        assertThat(rendered).contains("> 5").contains("< 7.5").contains("= true");
    }

    @Test
    void theSameParameterMayAppearTwice() {
        final String rendered = bind("SELECT c/uid/value " + FROM
                + "WHERE c/name/value = $x OR o/name/value = $x", Map.of("x", "same"));
        assertThat(rendered).contains("c/name/value = 'same'").contains("o/name/value = 'same'");
    }

    @Test
    void reportsTheBoundNames() {
        final AqlQuery query = AqlQueryParser.parse("SELECT c/uid/value " + FROM
                + "WHERE c/name/value = $b AND o/name/value = $a");
        assertThat(QueryParameterBinder.bind(query, Map.of("a", "1", "b", "2"))).containsExactly("b", "a");
    }

    // ---- refusals: 400, naming parameters and never values --------------------

    @Test
    void unboundParameterIsRefusedByName() {
        assertInvalid("SELECT c/uid/value " + FROM + "WHERE c/name/value = $nm AND o/name/value = $on",
                Map.of("nm", "x"), "unbound", "on");
    }

    @Test
    void unknownParameterIsRefusedByName() {
        assertInvalid("SELECT c/uid/value " + FROM + "WHERE c/name/value = $nm",
                Map.of("nm", "x", "extra", "y"), "unknown", "extra");
    }

    @Test
    void nullAndContainerValuesAreRefused() {
        final Map<String, Object> withNull = new HashMap<>();
        withNull.put("nm", null);
        assertInvalid("SELECT c/uid/value " + FROM + "WHERE c/name/value = $nm", withNull, "parameter", "nm");
        assertInvalid("SELECT c/uid/value " + FROM + "WHERE c/name/value = $nm",
                Map.of("nm", Map.of("k", "v")), "parameter", "nm");
        assertInvalid("SELECT c/uid/value " + FROM + "WHERE c/name/value = $nm",
                Map.of("nm", List.of("a")), "parameter", "nm");
    }

    @Test
    void aNumberAfterLikeIsRefused() {
        assertInvalid("SELECT c/uid/value " + FROM + "WHERE c/name/value LIKE $pat", Map.of("pat", 42),
                "parameter", "pat");
    }

    @Test
    void aBooleanInAPathPredicateIsRefused() {
        assertInvalid("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c[name/value=$nm]",
                Map.of("nm", true), "parameter", "nm");
    }

    @Test
    @DisplayName("a value carrying the rewriter's placeholder can never reach the substitution step")
    void placeholderInAValueIsRefused() {
        assertInvalid("SELECT c/uid/value " + FROM + "WHERE c/name/value = $nm",
                Map.of("nm", "x" + AqlRewriter.EHR_ID_PLACEHOLDER + "y"), "parameter", "nm");
        assertThatThrownBy(() -> bind("SELECT c/uid/value " + FROM + "WHERE c/name/value = $nm",
                Map.of("nm", AqlRewriter.EHR_ID_PLACEHOLDER)))
                .hasMessageNotContaining(AqlRewriter.EHR_ID_PLACEHOLDER);
    }

    @Test
    void errorsNeverEchoTheValue() {
        assertThatThrownBy(() -> bind("SELECT c/uid/value " + FROM + "WHERE c/name/value LIKE $pat",
                Map.of("pat", 9999031234L)))
                .hasMessageNotContaining("9999031234")
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.details().toString()).doesNotContain("9999031234"));
    }

    // ---- the backstop ---------------------------------------------------------

    @Test
    void theBackstopIsQuoteAware() {
        QueryParameterBinder.assertNoUnboundParameter(
                "SELECT c FROM EHR e WHERE e/ehr_id/value = '" + AqlRewriter.EHR_ID_PLACEHOLDER + "'");
        QueryParameterBinder.assertNoUnboundParameter("SELECT c FROM EHR e WHERE c/name/value = 'costs $5'");
        QueryParameterBinder.assertNoUnboundParameter("SELECT c FROM EHR e WHERE c/name/value = 'a\\'$b'");
        assertThatThrownBy(() -> QueryParameterBinder.assertNoUnboundParameter(
                "SELECT c FROM EHR e WHERE c/name/value = $nm"))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_QUERY_PARAMETER_INVALID));
    }

    // ---- the reason binding happens before analysis ---------------------------

    @Test
    @DisplayName("a bound $patient_id on the subject path is the subject, and is rewritten to ehr_id")
    void boundSubjectParameterIsSeenAsTheSubject() {
        final AqlQuery query = AqlQueryParser.parse("SELECT c/uid/value AS u " + FROM
                + "WHERE e/ehr_status/subject/external_ref/id/value = $patient_id AND c/name/value = $nm");
        QueryParameterBinder.bind(query, Map.of("patient_id", "9999031234", "nm", "Encounter"));

        final SubjectAnalysis analysis = SubjectPredicateExtractor.analyse(query);
        assertThat(analysis.subjectId()).isEqualTo("9999031234");

        final String dispatched = AqlRewriter.forNode(AqlRewriter.toDispatchTemplate(query, analysis), EHR_ID);
        assertThat(dispatched)
                .contains("e/ehr_id/value = '" + EHR_ID + "'")
                .contains("c/name/value = 'Encounter'")
                .doesNotContain("9999031234")
                .doesNotContain("$");
    }

    /**
     * A numeric patient identifier — a Dutch BSN is one — arrives as a JSON
     * number or a bare {@code GET} value. The digits are the identifier;
     * resolution consumes them and no node sees the operand's type.
     */
    @Test
    @DisplayName("a numeric $patient_id is the subject too, as its digits")
    void numericSubjectParameterIsSeenAsTheSubject() {
        final AqlQuery query = AqlQueryParser.parse("SELECT c/uid/value AS u " + FROM
                + "WHERE e/ehr_status/subject/external_ref/id/value = $patient_id");
        QueryParameterBinder.bind(query, Map.of("patient_id", 9999031234L));

        final SubjectAnalysis analysis = SubjectPredicateExtractor.analyse(query);
        assertThat(analysis.subjectId()).isEqualTo("9999031234");
        assertThat(AqlRewriter.forNode(AqlRewriter.toDispatchTemplate(query, analysis), EHR_ID))
                .contains("e/ehr_id/value = '" + EHR_ID + "'")
                .doesNotContain("9999031234");
    }

    @Test
    void inferenceFromQueryStringValues() {
        assertThat(QueryParameterBinder.inferFromString("123")).isEqualTo(123L);
        assertThat(QueryParameterBinder.inferFromString("-4")).isEqualTo(-4L);
        assertThat(QueryParameterBinder.inferFromString("1.5")).isEqualTo(1.5d);
        assertThat(QueryParameterBinder.inferFromString("true")).isEqualTo(true);
        assertThat(QueryParameterBinder.inferFromString("FALSE")).isEqualTo(false);
        assertThat(QueryParameterBinder.inferFromString("9999031234")).isEqualTo(9999031234L);
        assertThat(QueryParameterBinder.inferFromString("abc")).isEqualTo("abc");
        assertThat(QueryParameterBinder.inferFromString("1.2.3")).isEqualTo("1.2.3");
        assertThat(QueryParameterBinder.inferFromString("")).isEqualTo("");
        // only a round-tripping rendering is a number: a leading zero is data
        assertThat(QueryParameterBinder.inferFromString("0123")).isEqualTo("0123");
        assertThat(QueryParameterBinder.inferFromString("+5")).isEqualTo("+5");
        assertThat(QueryParameterBinder.inferFromString("1.50")).isEqualTo("1.50");
        assertThat(QueryParameterBinder.inferFromString("1e3")).isEqualTo("1e3");
    }
}
