// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.merge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A merged row plus the endpoint it came from (needed for ties and dedup), and
 * every endpoint that contributed it: one for a node's own row, more once
 * federation-level {@code DISTINCT} has collapsed equal rows from several
 * nodes. {@code contributors} is what {@code meta.federation.row_endpoints[]}
 * reports for the row (spec §9.5.1).
 */
public record RowWithProvenance(String endpointId, Map<String, Object> values, List<String> contributors) {

    public RowWithProvenance(final String endpointId, final Map<String, Object> values) {
        this(endpointId, values, List.of(endpointId));
    }

    /** This row, also attributed to {@code other}'s contributors; first-seen order. */
    public RowWithProvenance mergedWith(final RowWithProvenance other) {
        final LinkedHashSet<String> union = new LinkedHashSet<>(contributors);
        union.addAll(other.contributors());
        return new RowWithProvenance(endpointId, values, List.copyOf(union));
    }

    private static final Pattern VERSION_UID = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})::([^:]+)::(\\S+)");

    /** First OBJECT_VERSION_ID-shaped value in the row, or null. */
    public VersionUid versionUid() {
        for (final Object value : values.values()) {
            if (value instanceof String s) {
                final Matcher m = VERSION_UID.matcher(s);
                if (m.matches()) {
                    return new VersionUid(m.group(1), m.group(2), m.group(3));
                }
            }
        }
        return null;
    }

    public record VersionUid(String objectId, String creatingSystemId, String versionTreeId) {
    }
}
