// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.conformance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CP-39 / §13.4 / N25: a deployment MUST document its answer to five
 * authentication obligations. The spec scores this "as documentation at
 * admission, not per request" — there is no wire artefact — so the test is
 * that the documentation exists and answers each item by name.
 *
 * <p>This repository is a build, not a deployment, and its documented answer
 * to most items is that the surrounding deployment decides. That is still an
 * answer: §13.4's complaint is the deployment that "never notices it chose",
 * and a page that says which choices are delegated and to whom is what lets a
 * reviewer check that each was made.
 */
@Tag("CP-39")
class SecurityDecisionsDocumentedTest {

    private static final Path SECURITY_DOC = Path.of("docs", "security.md");

    /** One heading per §13.4 obligation, in the spec's own words. */
    private static final List<String> OBLIGATIONS = List.of(
            "## Which identity is verified across the trust boundary",
            "## Who authenticates the end user, and where that trust stops",
            "## Purpose of use",
            "## What the token is bound to",
            "## What the technique does not cover");

    @Test
    @DisplayName("docs/security.md answers each §13.4 obligation under its own heading")
    void everyObligationHasAHeading() throws IOException {
        assertThat(SECURITY_DOC).as("CP-39 is verified as documentation; the document must exist").exists();
        final String text = Files.readString(SECURITY_DOC);
        for (final String heading : OBLIGATIONS) {
            assertThat(text).as("§13.4 obligation not answered: %s", heading).contains(heading + "\n");
        }
    }

    @Test
    @DisplayName("the token-binding item answers both of its two decisions")
    void tokenBindingAnswersBothDecisions() throws IOException {
        // §13.4: "These are two decisions and a deployment MUST make both."
        final String text = Files.readString(SECURITY_DOC);
        final int start = text.indexOf("## What the token is bound to");
        final int end = text.indexOf("## What the technique does not cover");
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        final String section = text.substring(start, end).toLowerCase();
        assertThat(section).contains("bearer").contains("sender-constrained");
        assertThat(section).contains("transport identity");
    }
}
