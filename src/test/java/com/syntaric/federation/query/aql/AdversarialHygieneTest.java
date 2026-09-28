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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 *
 * <p>The {@link Carriers} group is CP-38 (§5.4.3): both patient-identifier
 * carriers are resolution input, and a clinician or facility predicate is
 * judged by its <em>value</em>, never refused for its path.
 */
@Tag("CP-26")
@Tag("CP-2")
@Tag("CP-7")
@Tag("TRACK-10")
class AdversarialHygieneTest {

    private static final String EHR_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PATIENT_ID = "9999031234";
    private static final String CLINICIAN_ID = "clin-4711";
    private static final String NAMESPACE = "urn:oid:2.16.840.1.113883.2.4.6.3";

    private static final String EXTERNAL_REF = "e/ehr_status/subject/external_ref/id/value";
    private static final String ENTRY_SUBJECT = "o/subject/identifiers/id";
    private static final String FROM = "FROM EHR e CONTAINS COMPOSITION c CONTAINS OBSERVATION o ";

    private static SubjectAnalysis analyse(String facade) {
        AqlQuery query = AqlQueryParser.parse(FederationDirectiveParser.parse(facade).strippedAql());
        return SubjectPredicateExtractor.analyse(query);
    }

    private static String rewrite(String facade) {
        AqlQuery query = AqlQueryParser.parse(FederationDirectiveParser.parse(facade).strippedAql());
        SubjectAnalysis analysis = SubjectPredicateExtractor.analyse(query);
        return AqlRewriter.forNode(AqlRewriter.toDispatchTemplate(query, analysis), EHR_ID);
    }

    private static void assertUnstrippable(String facade) {
        assertThatThrownBy(() -> rewrite(facade))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE));
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
        assertUnstrippable("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value LIKE '" + PATIENT_ID + "*'");
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
        assertUnstrippable("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c "
                + "WHERE e/ehr_status/subject/external_ref/id/value = '" + PATIENT_ID + "' "
                + "ORDER BY e/ehr_status/subject/external_ref/id/value");
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

    @Test
    void extractorRejectionMessageNeverEchoesTheValue() {
        assertThatThrownBy(() -> rewrite("SELECT c/uid/value " + FROM
                + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "' "
                + "AND c/composer/identifiers/id = '" + PATIENT_ID + "'"))
                .isInstanceOfSatisfying(FederationException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain(PATIENT_ID);
                    assertThat(String.valueOf(e.details())).doesNotContain(PATIENT_ID);
                });
    }

    /**
     * §5.4.3 / CP-38: the patient identifier is accepted in either carrier and
     * resolved on whichever the client used; every other identifier-bearing
     * path is judged by the value it carries.
     */
    @Nested
    @Tag("CP-38")
    @Tag("TRACK-2")
    class Carriers {

        @Test
        @DisplayName("An ENTRY-level subject predicate resolves and is stripped (§5.4.3)")
        void entrySubjectCarrierResolvesAndIsStripped() {
            String facade = "SELECT c/uid/value " + FROM
                    + "WHERE " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "'";

            SubjectAnalysis analysis = analyse(facade);
            assertThat(analysis.subjectId()).isEqualTo(PATIENT_ID);
            assertThat(analysis.carrier()).isEqualTo(SubjectAnalysis.Carrier.ENTRY_SUBJECT);

            String node = rewrite(facade);
            assertThat(node)
                    .contains("e/ehr_id/value = '" + EHR_ID + "'")
                    .doesNotContain(PATIENT_ID)
                    .doesNotContain("identifiers");
            IdentifierHygieneGuard.assertClean("AQL", node, List.of(PATIENT_ID));
        }

        @Test
        @DisplayName("The composition-rooted spelling of the ENTRY-level subject is the same carrier")
        void entrySubjectUnderContentPathIsTheSameCarrier() {
            String node = rewrite("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c "
                    + "WHERE c/content[openEHR-EHR-OBSERVATION.blood_pressure.v2]/subject/identifiers/id"
                    + " = '" + PATIENT_ID + "'");
            assertThat(node)
                    .contains("e/ehr_id/value = '" + EHR_ID + "'")
                    .doesNotContain(PATIENT_ID)
                    .doesNotContain("subject");
        }

        @Test
        @DisplayName("Both patient-identifier carriers resolve to the same dispatched AQL (§5.4.3)")
        void bothCarriersResolveIdentically() {
            // Same patient, same query, expressed via external_ref and via an
            // ENTRY-level subject PARTY_IDENTIFIED predicate -> identical
            // dispatched AQL, keyed on ehr_id, neither carrying the identifier.
            String select = "SELECT c/uid/value AS composition_id, c/context/start_time/value AS start_time "
                    + FROM + "WHERE ";
            String tail = " AND c/archetype_node_id = 'openEHR-EHR-COMPOSITION.encounter.v1' "
                    + "ORDER BY c/context/start_time/value DESC LIMIT 10";

            String viaExternalRef = rewrite(select + EXTERNAL_REF + " = '" + PATIENT_ID + "'" + tail);
            String viaEntrySubject = rewrite(select + ENTRY_SUBJECT + " = '" + PATIENT_ID + "'" + tail);

            assertThat(viaEntrySubject).isEqualTo(viaExternalRef);
            assertThat(viaExternalRef)
                    .contains("e/ehr_id/value = '" + EHR_ID + "'")
                    .doesNotContain(PATIENT_ID);
        }

        @Test
        @DisplayName("Naming the patient through both carriers with one value is one subject")
        void bothCarriersWithTheSameValueAreBothStripped() {
            String facade = "SELECT c/uid/value " + FROM
                    + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "' "
                    + "AND " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "'";

            assertThat(analyse(facade).carrier()).isEqualTo(SubjectAnalysis.Carrier.EXTERNAL_REF);
            String node = rewrite(facade);
            assertThat(node).doesNotContain(PATIENT_ID).doesNotContain("identifiers");
        }

        @Test
        @DisplayName("An ENTRY-level subject projection is re-injected, never read from a node (N5)")
        void entrySubjectProjectionIsReinjectedNotDispatched() {
            String facade = "SELECT " + ENTRY_SUBJECT + " AS patient_id, c/uid/value " + FROM
                    + "WHERE " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "'";

            SubjectAnalysis analysis = analyse(facade);
            assertThat(analysis.subjectProjections()).hasSize(1);
            assertThat(analysis.subjectProjections().get(0).columnName()).isEqualTo("patient_id");

            String node = rewrite(facade);
            assertThat(node).doesNotContain(PATIENT_ID).doesNotContain("identifiers");
        }

        // ---- the value the gateway did not resolve on (CP-26) ----------------

        @Test
        @DisplayName("An ENTRY-level subject carrying a value not resolved on is rejected (CP-26)")
        void entrySubjectPathCarryingAnotherValueIsRejected() {
            assertUnstrippable("SELECT c/uid/value " + FROM
                    + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "' "
                    + "AND " + ENTRY_SUBJECT + " = '0000000000'");
        }

        @Test
        @DisplayName("An ENTRY-level subject path in ORDER BY or under OR cannot be consumed")
        void entrySubjectPathOutsideResolutionIsRejected() {
            assertUnstrippable("SELECT c/uid/value " + FROM
                    + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "' "
                    + "ORDER BY " + ENTRY_SUBJECT);
            assertUnstrippable("SELECT c/uid/value " + FROM
                    + "WHERE " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "' "
                    + "OR c/name/value = 'x'");
        }

        @Test
        @DisplayName("Any other attribute of an ENTRY-level subject is patient-identifying and refused")
        void entrySubjectNameIsRejected() {
            assertUnstrippable("SELECT c/uid/value " + FROM
                    + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "' "
                    + "AND o/subject/name = 'Jane Doe'");
        }

        // ---- clinician and facility predicates (§5.4.3, CP-38) ---------------

        @Test
        @DisplayName("A composer predicate carrying a clinician id is dispatched, not refused")
        void clinicianPredicateOnComposerIsDispatched() {
            String node = rewrite("SELECT c/uid/value " + FROM
                    + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "' "
                    + "AND c/composer/identifiers/id = '" + CLINICIAN_ID + "'");
            assertThat(node)
                    .contains("c/composer/identifiers/id = '" + CLINICIAN_ID + "'")
                    .doesNotContain(PATIENT_ID);
            IdentifierHygieneGuard.assertClean("AQL", node, List.of(PATIENT_ID));
        }

        @Test
        @DisplayName("Facility and performer predicates are ordinary query material")
        void facilityAndPerformerPredicatesAreDispatched() {
            String node = rewrite("SELECT c/uid/value " + FROM
                    + "WHERE " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "' "
                    + "AND c/context/health_care_facility/identifiers/id = 'ura-123' "
                    + "AND c/context/participations/performer/identifiers/id = '" + CLINICIAN_ID + "'");
            assertThat(node)
                    .contains("health_care_facility/identifiers/id = 'ura-123'")
                    .contains("performer/identifiers/id = '" + CLINICIAN_ID + "'")
                    .doesNotContain(PATIENT_ID);
        }

        @Test
        @DisplayName("A DV_IDENTIFIER projection over the composer is dispatched")
        void clinicianProjectionIsDispatched() {
            String node = rewrite("SELECT c/composer/identifiers/id AS who, c/uid/value "
                    + FROM + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "'");
            assertThat(node)
                    .contains("c/composer/identifiers/id AS who")
                    .doesNotContain(PATIENT_ID);
        }

        @Test
        @DisplayName("The patient identifier smuggled through a composer path is rejected (CP-26)")
        void patientIdentifierSmuggledThroughComposerPathIsRejected() {
            assertUnstrippable("SELECT c/uid/value " + FROM
                    + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "' "
                    + "AND c/composer/identifiers/id = '" + PATIENT_ID + "'");
            // …wherever it sits: the value test is position-independent
            assertUnstrippable("SELECT c/uid/value " + FROM
                    + "WHERE " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "' "
                    + "AND (c/composer/identifiers/id = '" + PATIENT_ID + "' OR c/name/value = 'x')");
        }

        // ---- issuing namespace (§5.2, §5.4.3) --------------------------------

        @Test
        @DisplayName("external_ref/namespace supplies the namespace and is consumed")
        void externalRefNamespaceIsConsumedAndStripped() {
            String facade = "SELECT c/uid/value " + FROM
                    + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "' "
                    + "AND e/ehr_status/subject/external_ref/namespace = '" + NAMESPACE + "'";

            assertThat(analyse(facade).issuingNamespace()).isEqualTo(NAMESPACE);
            String node = rewrite(facade);
            assertThat(node)
                    .contains("e/ehr_id/value = '" + EHR_ID + "'")
                    .doesNotContain("namespace")
                    .doesNotContain(NAMESPACE)
                    .doesNotContain(PATIENT_ID);
        }

        @Test
        @DisplayName("identifiers/issuer supplies the namespace for an ENTRY-level subject")
        void entrySubjectIssuerIsConsumedAndStripped() {
            String facade = "SELECT c/uid/value " + FROM
                    + "WHERE o/subject/identifiers/issuer = '" + NAMESPACE + "' "
                    + "AND " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "' "
                    + "AND c/archetype_node_id = 'openEHR-EHR-COMPOSITION.encounter.v1'";

            assertThat(analyse(facade).issuingNamespace()).isEqualTo(NAMESPACE);
            String node = rewrite(facade);
            assertThat(node)
                    .contains("e/ehr_id/value = '" + EHR_ID + "'")
                    .contains("c/archetype_node_id = 'openEHR-EHR-COMPOSITION.encounter.v1'")
                    .doesNotContain("issuer")
                    .doesNotContain("subject")
                    .doesNotContain(PATIENT_ID);
        }

        @Test
        @DisplayName("identifiers/type is the fallback when no issuer is given; issuer wins over it")
        void entrySubjectTypeIsTheFallbackNamespace() {
            String base = "SELECT c/uid/value " + FROM + "WHERE " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "' ";

            assertThat(analyse(base + "AND o/subject/identifiers/type = 'BSN'").issuingNamespace())
                    .isEqualTo("BSN");
            assertThat(analyse(base + "AND o/subject/identifiers/type = 'BSN' "
                    + "AND o/subject/identifiers/issuer = '" + NAMESPACE + "'").issuingNamespace())
                    .isEqualTo(NAMESPACE);
            assertThat(rewrite(base + "AND o/subject/identifiers/type = 'BSN' "
                    + "AND o/subject/identifiers/issuer = '" + NAMESPACE + "'"))
                    .doesNotContain("type").doesNotContain("issuer");
        }

        @Test
        @DisplayName("No namespace in the query leaves it to the deployment default")
        void absentNamespaceIsNull() {
            assertThat(analyse("SELECT c/uid/value " + FROM
                    + "WHERE " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "'").issuingNamespace()).isNull();
            assertThat(analyse("SELECT c/uid/value " + FROM
                    + "WHERE " + EXTERNAL_REF + " = '" + PATIENT_ID + "'").issuingNamespace()).isNull();
        }

        @Test
        @DisplayName("Two different namespaces, or one resolution cannot read, are rejected")
        void unreadableNamespacesAreRejected() {
            String base = "SELECT c/uid/value " + FROM + "WHERE " + ENTRY_SUBJECT + " = '" + PATIENT_ID + "' ";
            assertUnstrippable(base + "AND o/subject/identifiers/issuer = 'a' "
                    + "AND o/subject/identifiers/issuer = 'b'");
            assertUnstrippable(base + "AND (o/subject/identifiers/issuer = 'a' OR c/name/value = 'x')");
            assertUnstrippable(base + "AND o/subject/identifiers/issuer LIKE 'urn:*'");
        }
    }
}
