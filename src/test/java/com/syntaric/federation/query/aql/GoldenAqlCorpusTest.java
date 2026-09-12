// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql;

import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.aql.analysis.AggregatePolicy;
import com.syntaric.federation.query.aql.analysis.LimitOffsetPolicy;
import com.syntaric.federation.query.aql.analysis.SubjectAnalysis;
import com.syntaric.federation.query.aql.analysis.SubjectPredicateExtractor;
import com.syntaric.federation.query.aql.directive.FederationDirectiveParser;
import com.syntaric.federation.query.aql.directive.PreprocessedAql;
import com.syntaric.federation.query.aql.rewrite.AqlRewriter;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.parser.AqlQueryParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan M3 verification: every golden case pins the EXACT per-node wire AQL
 * (or the exact rejection code) for one façade AQL. The corpus is the contract
 * of the rewrite envelope — any renderer/rewriter drift fails here first.
 *
 * <p>Executes spec §16.3 Track 2: the gateway resolves {@code subject} to a
 * per-node {@code ehr_id} and dispatches AQL keyed on it, and nodes never
 * receive {@code subject}. The corpus asserts this on the dispatched wire AQL
 * itself, which is the form Track 2 asks for ("inspect node-side AQL").
 */
@Tag("CP-2")
@Tag("CP-4")
@Tag("CP-22")
@Tag("CP-32")
@Tag("TRACK-2")
class GoldenAqlCorpusTest {

    /** The corpus is exercised as if fanning out to two nodes. */
    private static final int NODE_COUNT = 2;

    @TestFactory
    Stream<DynamicTest> goldenCorpus() throws IOException, URISyntaxException {
        Path dir = Paths.get(getClass().getClassLoader().getResource("aql-golden").toURI());
        List<Path> cases;
        try (Stream<Path> files = Files.list(dir)) {
            cases = files.filter(p -> p.toString().endsWith(".case")).sorted().toList();
        }
        assertThat(cases).isNotEmpty();
        return cases.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(),
                () -> runCase(parse(file))));
    }

    private void runCase(Map<String, String> sections) {
        String facade = sections.get("facade");
        String expected = sections.get("expected");
        if (expected.startsWith("ERROR:")) {
            String code = expected.substring("ERROR:".length());
            assertThatThrownBy(() -> pipeline(sections))
                    .isInstanceOfSatisfying(FederationException.class,
                            e -> assertThat(e.code().name()).isEqualTo(code));
        } else {
            String actual = pipeline(sections);
            assertThat(actual).isEqualTo(expected);
        }
        assertThat(facade).isNotBlank();
    }

    /** The exact production pipeline: strip → parse → analyse → policies → rewrite → substitute. */
    private String pipeline(Map<String, String> sections) {
        PreprocessedAql pre = FederationDirectiveParser.parse(sections.get("facade"));
        if (sections.containsKey("endpoints")) {
            assertThat(pre.endpointIds())
                    .containsExactly(sections.get("endpoints").split(","));
        }
        AqlQuery query = AqlQueryParser.parse(pre.strippedAql());
        SubjectAnalysis analysis = SubjectPredicateExtractor.analyse(query);
        AggregatePolicy.check(query, NODE_COUNT);
        LimitOffsetPolicy.check(query, NODE_COUNT);
        String template = AqlRewriter.toDispatchTemplate(query, analysis);
        String ehrId = sections.get("ehr_id");
        return "NONE".equals(ehrId) ? template : AqlRewriter.forNode(template, ehrId);
    }

    private Map<String, String> parse(Path file) {
        try {
            Map<String, String> sections = new LinkedHashMap<>();
            String current = null;
            List<String> buffer = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("== ")) {
                    if (current != null) {
                        sections.put(current, String.join("\n", buffer).trim());
                    }
                    current = line.substring(3).trim();
                    buffer.clear();
                } else {
                    buffer.add(line);
                }
            }
            if (current != null) {
                sections.put(current, String.join("\n", buffer).trim());
            }
            return sections;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
