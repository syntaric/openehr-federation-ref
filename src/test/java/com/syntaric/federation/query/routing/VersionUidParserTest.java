// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.routing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("CP-14")
class VersionUidParserTest {

    @Test
    void parsesObjectVersionId() {
        var uid = VersionUidParser.parse(
                "8849182a-1d4b-4e3d-a3f3-f303d2f4f34b::cdr1.rso.nl::1").orElseThrow();
        assertThat(uid.objectId()).isEqualTo("8849182a-1d4b-4e3d-a3f3-f303d2f4f34b");
        assertThat(uid.creatingSystemId()).isEqualTo("cdr1.rso.nl");
        assertThat(uid.versionTreeId()).isEqualTo("1");
    }

    @Test
    void parsesBranchedVersionTreeIds() {
        var uid = VersionUidParser.parse("abc::sys::1.2.1").orElseThrow();
        assertThat(uid.versionTreeId()).isEqualTo("1.2.1");
    }

    @Test
    void plainUuidIsNotAVersionUid() {
        assertThat(VersionUidParser.parse("8849182a-1d4b-4e3d-a3f3-f303d2f4f34b")).isEmpty();
    }

    @Test
    void findsUidInsideARequestPath() {
        var uid = VersionUidParser.findInPath(
                "/v1/ehr/x/composition/8849182a-1d4b-4e3d-a3f3-f303d2f4f34b::cdr2::3").orElseThrow();
        assertThat(uid.creatingSystemId()).isEqualTo("cdr2");
    }
}
