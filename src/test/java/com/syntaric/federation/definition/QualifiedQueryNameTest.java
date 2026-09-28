// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.definition;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ITS-REST {@code [{namespace}::]{query-name}}, and the one name the ad-hoc route reserves. */
@Tag("CP-40")
class QualifiedQueryNameTest {

    @Test
    void parsesNamespacedAndBareNames() {
        assertThat(QualifiedQueryName.parse("org.openehr::compositions"))
                .isEqualTo(new QualifiedQueryName("org.openehr", "compositions"));
        assertThat(QualifiedQueryName.parse("encounters-v2"))
                .isEqualTo(new QualifiedQueryName(null, "encounters-v2"));
        assertThat(QualifiedQueryName.parse("a.b::c_d").qualified()).isEqualTo("a.b::c_d");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "::name", "ns::", "ns::na me", "ns/x::name", "name'", "a::b::c", "-lead"})
    void rejectsMalformedNames(final String qualified) {
        assertThatThrownBy(() -> QualifiedQueryName.parse(qualified))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_STORED_QUERY_INVALID));
    }

    @Test
    void theAdHocRouteNameIsReserved() {
        assertThatThrownBy(() -> QualifiedQueryName.parse("aql"))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_STORED_QUERY_INVALID));
        assertThatThrownBy(() -> QualifiedQueryName.parse("AQL"))
                .isInstanceOf(FederationException.class);
        // …but only the bare literal: a namespaced `aql` is a different path segment
        assertThat(QualifiedQueryName.parse("org::aql").qualified()).isEqualTo("org::aql");
    }
}
