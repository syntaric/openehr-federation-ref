// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.directive;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("CP-6")
@Tag("CP-28")
class FederationDirectiveParserTest {

    @Test
    void queryWithoutDirectivePassesThroughUntouched() {
        String aql = "SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c";
        PreprocessedAql pre = FederationDirectiveParser.parse(aql);
        assertThat(pre.directivePresent()).isFalse();
        assertThat(pre.strippedAql()).isEqualTo(aql);
    }

    @Test
    void organisationDirectiveIsSupported() {
        PreprocessedAql pre = FederationDirectiveParser.parse(
                "SELECT c/uid/value FROM ORGANISATION [ \"org-a\" ] CONTAINS EHR e "
                        + "CONTAINS COMPOSITION c");
        assertThat(pre.organisationIds()).containsExactly("org-a");
        assertThat(pre.strippedAql())
                .isEqualTo("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c");
    }

    @Test
    void directiveKeywordInsideAStringLiteralIsNotADirective() {
        String aql = "SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c "
                + "WHERE c/name/value = 'FROM ENDPOINT p [\"node_1\"]'";
        PreprocessedAql pre = FederationDirectiveParser.parse(aql);
        assertThat(pre.directivePresent()).isFalse();
        assertThat(pre.strippedAql()).isEqualTo(aql);
    }

    @Test
    void aliasUseInWhereIsRejected() {
        assertThatThrownBy(() -> FederationDirectiveParser.parse(
                "SELECT c/uid/value FROM ENDPOINT p [ \"node_1\" ] CONTAINS EHR e "
                        + "CONTAINS COMPOSITION c WHERE p/id = 'node_1'"))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_QUERY_UNSUPPORTED));
    }

    @Test
    void unknownEndpointAttributeIsRejected() {
        assertThatThrownBy(() -> FederationDirectiveParser.parse(
                "SELECT p/base_url, c/uid/value FROM ENDPOINT p [ \"node_1\" ] CONTAINS EHR e "
                        + "CONTAINS COMPOSITION c"))
                .isInstanceOf(FederationException.class);
    }

    @Test
    void selectOfOnlyEndpointAttributesIsRejected() {
        assertThatThrownBy(() -> FederationDirectiveParser.parse(
                "SELECT p/id FROM ENDPOINT p [ \"node_1\" ] CONTAINS EHR e CONTAINS COMPOSITION c"))
                .isInstanceOf(FederationException.class);
    }

    @Test
    void emptyEndpointListIsRejected() {
        assertThatThrownBy(() -> FederationDirectiveParser.parse(
                "SELECT c/uid/value FROM ENDPOINT p [ ] CONTAINS EHR e CONTAINS COMPOSITION c"))
                .isInstanceOf(FederationException.class);
    }

    @Test
    @Tag("CP-41")
    void listlessEndpointDirectiveBindsTheAliasAndNamesNoNodes() {
        PreprocessedAql pre = FederationDirectiveParser.parse(
                "SELECT p/id AS endpoint_id, p/organisation AS org, c/uid/value FROM ENDPOINT p "
                        + "CONTAINS EHR e CONTAINS COMPOSITION c");
        assertThat(pre.directivePresent()).isTrue();
        assertThat(pre.endpointIds()).isEmpty();
        assertThat(pre.organisationIds()).isEmpty();
        assertThat(pre.projections()).extracting(PreprocessedAql.EndpointProjection::columnName)
                .containsExactly("endpoint_id", "org");
        assertThat(pre.strippedAql())
                .isEqualTo("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c");
    }

    @Test
    @Tag("CP-41")
    void listlessDirectiveWithoutProjectionsStripsToTheUndirectedQuery() {
        PreprocessedAql pre = FederationDirectiveParser.parse(
                "SELECT c/uid/value FROM ENDPOINT p CONTAINS EHR e CONTAINS COMPOSITION c");
        assertThat(pre.endpointIds()).isEmpty();
        assertThat(pre.projections()).isEmpty();
        assertThat(pre.strippedAql())
                .isEqualTo("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c");
    }

    @Test
    @Tag("CP-41")
    void listlessDirectiveWithoutAnAliasIsRejected() {
        assertThatThrownBy(() -> FederationDirectiveParser.parse(
                "SELECT c/uid/value FROM ENDPOINT CONTAINS EHR e CONTAINS COMPOSITION c"))
                .isInstanceOfSatisfying(FederationException.class, e -> {
                    assertThat(e.code()).isEqualTo(FedErrorCode.FED_AQL_INVALID);
                    assertThat(e.getMessage()).contains("must bind an alias");
                });
    }

    @Test
    @Tag("CP-41")
    void listlessOrganisationDirectiveIsRejected() {
        assertThatThrownBy(() -> FederationDirectiveParser.parse(
                "SELECT o/organisation, c/uid/value FROM ORGANISATION o CONTAINS EHR e "
                        + "CONTAINS COMPOSITION c"))
                .isInstanceOfSatisfying(FederationException.class, e -> {
                    assertThat(e.code()).isEqualTo(FedErrorCode.FED_AQL_INVALID);
                    assertThat(e.getMessage()).contains("ORGANISATION requires a list");
                });
    }

    @Test
    @Tag("CP-41")
    void organisationDirectiveBindsAnAliasForProjections() {
        PreprocessedAql pre = FederationDirectiveParser.parse(
                "SELECT o/id AS endpoint_id, c/uid/value FROM ORGANISATION o [ \"org-a\" ] "
                        + "CONTAINS EHR e CONTAINS COMPOSITION c");
        assertThat(pre.organisationIds()).containsExactly("org-a");
        assertThat(pre.projections()).hasSize(1);
        assertThat(pre.strippedAql())
                .isEqualTo("SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c");
    }

    @Test
    void projectionAliasesAreHonoured() {
        PreprocessedAql pre = FederationDirectiveParser.parse(
                "SELECT p/organisation AS org_name, c/uid/value FROM ENDPOINT p [ \"n1\" ] "
                        + "CONTAINS EHR e CONTAINS COMPOSITION c");
        assertThat(pre.projections()).hasSize(1);
        assertThat(pre.projections().get(0).columnName()).isEqualTo("org_name");
        assertThat(pre.projections().get(0).position()).isEqualTo(0);
    }
}
