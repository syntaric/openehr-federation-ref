// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.definition;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §12.7: versions are ITS-REST's own strict semver segment, and "latest" is numeric, not lexical. */
@Tag("CP-40")
class SemVerTest {

    @Test
    void parsesStrictTriples() {
        assertThat(SemVer.parse("1.2.3")).isEqualTo(new SemVer(1, 2, 3));
        assertThat(SemVer.parse("0.0.0")).isEqualTo(new SemVer(0, 0, 0));
        assertThat(SemVer.parse("10.0.1").toString()).isEqualTo("10.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1", "v1.0.0", "1.0.0-rc1", "1.0.0+build", "01.0.0", "1.0.0.0", "", "a.b.c"})
    void rejectsEverythingElse(final String version) {
        assertThatThrownBy(() -> SemVer.parse(version))
                .isInstanceOfSatisfying(FederationException.class,
                        e -> assertThat(e.code()).isEqualTo(FedErrorCode.FED_STORED_QUERY_INVALID));
    }

    @Test
    void ordersNumericallyNotLexically() {
        final List<SemVer> versions = Stream.of("1.10.0", "1.2.0", "1.9.9", "2.0.0", "1.2.1")
                .map(SemVer::parse).sorted().toList();
        assertThat(versions.stream().map(SemVer::toString))
                .containsExactly("1.2.0", "1.2.1", "1.9.9", "1.10.0", "2.0.0");
    }
}
