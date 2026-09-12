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
    void projectionAliasesAreHonoured() {
        PreprocessedAql pre = FederationDirectiveParser.parse(
                "SELECT p/organisation AS org_name, c/uid/value FROM ENDPOINT p [ \"n1\" ] "
                        + "CONTAINS EHR e CONTAINS COMPOSITION c");
        assertThat(pre.projections()).hasSize(1);
        assertThat(pre.projections().get(0).columnName()).isEqualTo("org_name");
        assertThat(pre.projections().get(0).position()).isEqualTo(0);
    }
}
