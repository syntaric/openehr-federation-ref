// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Opt-in {@code version-identity} deduplication (spec §10.2): rows are grouped
 * by the {@code object_id} segment of their version uid; within a group the
 * originating copy is kept — the row read from the endpoint whose node
 * {@code system_id} equals the uid's {@code creating_system_id} — with a
 * deterministic fallback (lowest {@code endpoint_id}). Suppressed endpoints are
 * recorded so clients can tell other copies exist (§10.3). Rows without a
 * version uid pass through untouched.
 */
public final class VersionIdentityDeduplicator {

    private VersionIdentityDeduplicator() {
    }

    public record Result(List<RowWithProvenance> rows, List<ResultEnvelope.Dedup.Suppressed> suppressed) {
    }

    public static Result deduplicate(final List<RowWithProvenance> rows,
                                     final Function<String, String> systemIdOfEndpoint) {
        final Map<String, List<RowWithProvenance>> byObjectId = new LinkedHashMap<>();
        final List<RowWithProvenance> result = new ArrayList<>();
        final List<ResultEnvelope.Dedup.Suppressed> suppressed = new ArrayList<>();

        for (final RowWithProvenance row : rows) {
            final RowWithProvenance.VersionUid uid = row.versionUid();
            if (uid != null) {
                byObjectId.computeIfAbsent(uid.objectId(), k -> new ArrayList<>()).add(row);
            }
        }

        final Map<String, RowWithProvenance> keeperByObjectId = new LinkedHashMap<>();
        for (final Map.Entry<String, List<RowWithProvenance>> group : byObjectId.entrySet()) {
            final RowWithProvenance keeper = group.getValue().stream()
                    .min(Comparator
                            .comparing((RowWithProvenance r) -> !isOriginating(r, systemIdOfEndpoint))
                            .thenComparing(RowWithProvenance::endpointId))
                    .orElseThrow();
            keeperByObjectId.put(group.getKey(), keeper);
        }

        // Emit in original merge order, dropping every non-keeper copy.
        for (final RowWithProvenance row : rows) {
            final RowWithProvenance.VersionUid uid = row.versionUid();
            if (uid == null || keeperByObjectId.get(uid.objectId()) == row) {
                result.add(row);
            } else {
                suppressed.add(new ResultEnvelope.Dedup.Suppressed(uid.objectId(), row.endpointId()));
            }
        }
        return new Result(result, suppressed);
    }

    private static boolean isOriginating(final RowWithProvenance row, final Function<String, String> systemIdOfEndpoint) {
        final RowWithProvenance.VersionUid uid = row.versionUid();
        final String systemId = systemIdOfEndpoint.apply(row.endpointId());
        return uid != null && systemId != null && systemId.equals(uid.creatingSystemId());
    }
}
