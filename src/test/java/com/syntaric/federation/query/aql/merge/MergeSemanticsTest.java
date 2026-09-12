// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.merge;

import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.fanout.NodeOutcome;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.parser.AqlQueryParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * N39 merge properties: merged sort == single-list sort, deterministic ties.
 *
 * <p>Executes the dedup/DISTINCT half of spec §16.3 Track 5: default
 * pass-through leaves duplicates, and DISTINCT / ORDER BY work at the Tier.
 */
@Tag("CP-8")
@Tag("CP-32")
@Tag("TRACK-5")
class MergeSemanticsTest {

    /**
     * Column shapes as the two demo CDRs actually report them — they disagree,
     * and that disagreement is the point of pinning both. Verified against the
     * demo stack: EHRbase echoes the FROM alias in {@code path}, FerroEHR strips
     * it. A fixture asserting either one alone encodes a contract only half the
     * world satisfies, which is how the ORDER BY bug stayed invisible.
     */
    private static final List<NodeOutcome.Column> EHRBASE_COLUMNS = List.of(
            new NodeOutcome.Column("start_time", "c/context/start_time/value"),
            new NodeOutcome.Column("composition_id", "c/uid/value"));

    private static final List<NodeOutcome.Column> FERROEHR_COLUMNS = List.of(
            new NodeOutcome.Column("start_time", "/context/start_time/value"),
            new NodeOutcome.Column("composition_id", "/uid/value"));

    /** Sorting must not depend on which vendor answered; default to FerroEHR's shape. */
    private Comparator<RowWithProvenance> comparator(String aql) {
        return comparator(aql, FERROEHR_COLUMNS);
    }

    private Comparator<RowWithProvenance> comparator(String aql, List<NodeOutcome.Column> columns) {
        AqlQuery query = AqlQueryParser.parse(aql);
        return OrderByComparator.forQuery(query, columns);
    }

    private static final String ORDERED_AQL =
            "SELECT c/context/start_time/value AS start_time, c/uid/value AS composition_id "
                    + "FROM EHR e CONTAINS COMPOSITION c ORDER BY c/context/start_time/value DESC";

    @Test
    void orderByResolvesAgainstEitherVendorColumnPathShape() {
        RowWithProvenance older = row("node_1", "2026-01-01T10:00", uid("aa"));
        RowWithProvenance newer = row("node_1", "2026-06-01T10:00", uid("bb"));
        for (List<NodeOutcome.Column> columns : List.of(EHRBASE_COLUMNS, FERROEHR_COLUMNS)) {
            assertThat(List.of(older, newer).stream().sorted(comparator(ORDERED_AQL, columns)).toList())
                    .as("ORDER BY must resolve regardless of how the node renders column paths")
                    .containsExactly(newer, older);
        }
    }

    @Test
    void orderByOnAPathThatIsNotSelectedIsRejected() {
        assertThatThrownBy(() -> comparator(
                "SELECT c/uid/value AS composition_id FROM EHR e CONTAINS COMPOSITION c "
                        + "ORDER BY c/context/start_time/value DESC"))
                .isInstanceOf(FederationException.class)
                .hasMessageContaining("must appear in the SELECT list");
    }

    private RowWithProvenance row(String endpoint, String startTime, String uid) {
        return new RowWithProvenance(endpoint, Map.of(
                "start_time", startTime,
                "composition_id", uid));
    }

    @Test
    void mergedSortEqualsSingleListSort() {
        Comparator<RowWithProvenance> comparator = comparator(
                "SELECT c/context/start_time/value AS start_time, c/uid/value AS composition_id "
                        + "FROM EHR e CONTAINS COMPOSITION c ORDER BY c/context/start_time/value DESC");
        Random random = new Random(42);
        List<RowWithProvenance> all = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            all.add(row("node_" + (1 + random.nextInt(3)),
                    "2026-0" + (1 + random.nextInt(9)) + "-01T10:0" + random.nextInt(10),
                    "aaaaaaaa-0000-0000-0000-" + String.format("%012d", i)
                            + "::cdr" + (1 + random.nextInt(3)) + "::1"));
        }
        // Split per node (as a fan-out would), sort per node, merge, re-sort.
        List<RowWithProvenance> merged = new ArrayList<>();
        for (String node : List.of("node_1", "node_2", "node_3")) {
            merged.addAll(all.stream().filter(r -> r.endpointId().equals(node)).sorted(comparator).toList());
        }
        List<RowWithProvenance> tierSorted = merged.stream().sorted(comparator).toList();
        List<RowWithProvenance> reference = all.stream().sorted(comparator).toList();
        assertThat(tierSorted).isEqualTo(reference);
    }

    @Test
    void tiesBreakDeterministicallyOnEndpointThenUid() {
        Comparator<RowWithProvenance> comparator = comparator(
                "SELECT c/context/start_time/value AS start_time, c/uid/value AS composition_id "
                        + "FROM EHR e CONTAINS COMPOSITION c ORDER BY c/context/start_time/value");
        RowWithProvenance a = row("node_2", "2026-01-01T10:00", uid("aa"));
        RowWithProvenance b = row("node_1", "2026-01-01T10:00", uid("bb"));
        RowWithProvenance c = row("node_1", "2026-01-01T10:00", uid("aa"));
        List<RowWithProvenance> sorted = List.of(a, b, c).stream().sorted(comparator).toList();
        assertThat(sorted).containsExactly(c, b, a);
    }

    private String uid(String prefix) {
        return prefix + "aaaaaa-0000-0000-0000-000000000001::cdr1::1";
    }

    @Test
    void globalTopNIsContainedInPerNodeTopN() {
        Comparator<RowWithProvenance> comparator = comparator(
                "SELECT c/context/start_time/value AS start_time, c/uid/value AS composition_id "
                        + "FROM EHR e CONTAINS COMPOSITION c ORDER BY c/context/start_time/value DESC LIMIT 5");
        Random random = new Random(7);
        List<RowWithProvenance> all = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            all.add(row("node_" + (1 + random.nextInt(2)),
                    "2026-01-01T" + String.format("%02d", random.nextInt(24)) + ":00",
                    "aaaaaaaa-0000-0000-0000-" + String.format("%012d", i) + "::cdr1::1"));
        }
        int n = 5;
        // per-node top n (what LIMIT n per node dispatches back) …
        List<RowWithProvenance> unionOfTopN = new ArrayList<>();
        for (String node : List.of("node_1", "node_2")) {
            unionOfTopN.addAll(all.stream().filter(r -> r.endpointId().equals(node))
                    .sorted(comparator).limit(n).toList());
        }
        // … re-sorted and re-limited must equal the true global top n
        List<RowWithProvenance> federated = unionOfTopN.stream().sorted(comparator).limit(n).toList();
        List<RowWithProvenance> reference = all.stream().sorted(comparator).limit(n).toList();
        assertThat(federated).isEqualTo(reference);
    }

    @Test
    void dedupKeepsOriginatingCopyAndRecordsSuppression() {
        String sharedUid = "11111111-1111-1111-1111-111111111111::cdr1.example::1";
        RowWithProvenance origin = new RowWithProvenance("node_1", Map.of("composition_id", sharedUid));
        RowWithProvenance copy = new RowWithProvenance("node_2", Map.of("composition_id", sharedUid));
        VersionIdentityDeduplicator.Result result = VersionIdentityDeduplicator.deduplicate(
                List.of(copy, origin),
                endpointId -> endpointId.equals("node_1") ? "cdr1.example" : "cdr2.example");
        assertThat(result.rows()).containsExactly(origin);
        assertThat(result.suppressed()).containsExactly(
                new ResultEnvelope.Dedup.Suppressed("11111111-1111-1111-1111-111111111111", "node_2"));
    }

    @Test
    void dedupWithoutAnOriginatingCopyFallsBackDeterministically() {
        String sharedUid = "11111111-1111-1111-1111-111111111111::cdr9.example::1";
        RowWithProvenance a = new RowWithProvenance("node_2", Map.of("composition_id", sharedUid));
        RowWithProvenance b = new RowWithProvenance("node_1", Map.of("composition_id", sharedUid));
        VersionIdentityDeduplicator.Result result = VersionIdentityDeduplicator.deduplicate(
                List.of(a, b), endpointId -> "unrelated.system");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).endpointId()).isEqualTo("node_1");
    }

    @Test
    void distinctVersionsAreNeverDeduplicated() {
        RowWithProvenance v1 = new RowWithProvenance("node_1",
                Map.of("composition_id", "11111111-1111-1111-1111-111111111111::cdr1::1"));
        RowWithProvenance v2 = new RowWithProvenance("node_1",
                Map.of("composition_id", "22222222-2222-2222-2222-222222222222::cdr1::1"));
        VersionIdentityDeduplicator.Result result =
                VersionIdentityDeduplicator.deduplicate(List.of(v1, v2), e -> "cdr1");
        assertThat(result.rows()).containsExactly(v1, v2);
        assertThat(result.suppressed()).isEmpty();
    }
}
