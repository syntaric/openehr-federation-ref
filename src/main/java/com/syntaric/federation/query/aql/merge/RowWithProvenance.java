// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.merge;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A merged row plus the endpoint it came from (needed for ties and dedup). */
public record RowWithProvenance(String endpointId, Map<String, Object> values) {

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
