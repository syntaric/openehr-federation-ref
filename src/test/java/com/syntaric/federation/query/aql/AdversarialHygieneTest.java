// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.aql.analysis.IdentifierHygieneGuard;
import com.syntaric.federation.query.aql.analysis.SubjectAnalysis;
import com.syntaric.federation.query.aql.analysis.SubjectPredicateExtractor;
import com.syntaric.federation.query.aql.directive.FederationDirectiveParser;
import com.syntaric.federation.query.aql.rewrite.AqlRewriter;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.parser.AqlQueryParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * N33/CP-26 adversarial corpus: however the identifier is smuggled in, either
 * it is provably absent from the composed wire form or the query dies with 400.
 *
 * <p>Executes the query-surface half of spec §16.3 Track 10: the same directly
 * identifying identifier supplied four ways (external_ref predicate,
 * PARTY_IDENTIFIED/DV_IDENTIFIER predicate, SELECT projection, query
 * string/header), asserting zero occurrences in the dispatched form or a 400.
 * The converse check — a COMPOSITION with a DV_IDENTIFIER in its content must
 * arrive byte-identical — is in
 * {@link com.syntaric.federation.it.ProxyByteIdentityIT}.
 */
@Tag("CP-26")
@Tag("CP-2")
@Tag("CP-7")
class AdversarialHygieneTest {

    private static final String EHR_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PATIENT_ID = "9999031234";

    private String rewrite(String facade) {
        AqlQuery query = AqlQueryParser.parse(FederationDirectiveParser.parse(facade).strippedAql());
        SubjectAnalysis analysis = SubjectPredicateExtractor.analyse(query);
        String node = AqlRewriter.forNode(AqlRewriter.toDispatchTemplate(query, analysis), EHR_ID);
        return node;
    }

    @Test
    void canonicalPredicateLeavesNoTraceInWireAql() {
        String node = rewrite("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'");
        assertThat(node).doesNotContain(PATIENT_ID);
        IdentifierHygieneGuard.assertClean("AQL", node, List.of(PATIENT_ID));
    }

    @Test
    void identifierHiddenInUnrelatedLiteralFailsTheFinalGate() {
        // The AST strip leaves the second literal untouched — the string-level
        // gate is the layer that must catch it.
        String node = rewrite("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "' "
                + "AND c/name/value = '" + PATIENT_ID + "'");
        assertThatThrownBy(() -> IdentifierHygieneGuard.assertClean("AQL", node, List.of(PATIENT_ID)))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_IDENTIFIER_HYGIENE));
    }

    @Test
    void identifierHiddenInLikePatternOnSubjectIsRejected() {
        assertThatThrownBy(() -> rewrite("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value LIKE '" + PATIENT_ID + "*'"))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE));
    }

    @Test
    void identifierInCommentNeverSurvivesRendering() {
        String node = rewrite("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c "
                + "-- patient " + PATIENT_ID + "\n"
                + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'");
        assertThat(node).doesNotContain(PATIENT_ID);
    }

    @Test
    void orderByOverSubjectPathIsRejected() {
        assertThatThrownBy(() -> rewrite("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "' "
                + "ORDER BY e/ehr_status/subject/external_ref/id/value"))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE));
    }

    @Test
    void dvIdentifierPathInSelectIsRejected() {
        assertThatThrownBy(() -> rewrite("SELECT c/composer/identifiers/id AS who, c/uid/value "
                + "FROM EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "'"))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE));
    }

    @Test
    void urlEncodedIdentifierIsCaughtByTheGate() {
        // "urn:oid:1.2|9999" URL-encodes to "urn%3Aoid%3A1.2%7C9999"
        String composed = "https://node/v1/query/aql?x=urn%3Aoid%3A1.2%7C9999";
        assertThatThrownBy(() -> IdentifierHygieneGuard.assertClean("URL", composed,
                List.of("urn:oid:1.2|9999")))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_IDENTIFIER_HYGIENE));
    }

    @Test
    void placeholderLiteralInFacadeQueryCannotBeUsedForInjection() {
        // Substituting the placeholder is only safe because a query carrying the
        // literal is rejected before dispatch (FederatedQueryService guard);
        // here we pin that forNode only accepts gateway-controlled UUIDs.
        assertThatThrownBy(() -> AqlRewriter.forNode("SELECT x", "not-a-uuid"))
                .isInstanceOf(FederationException.class);
    }

    @Test
    void hygieneRejectionMessageNeverEchoesTheValue() {
        try {
            IdentifierHygieneGuard.assertClean("AQL", "text " + PATIENT_ID, List.of(PATIENT_ID));
        } catch (FederationException e) {
            assertThat(e.getMessage()).doesNotContain(PATIENT_ID);
            assertThat(String.valueOf(e.details())).doesNotContain(PATIENT_ID);
            return;
        }
        throw new AssertionError("expected rejection");
    }
}
