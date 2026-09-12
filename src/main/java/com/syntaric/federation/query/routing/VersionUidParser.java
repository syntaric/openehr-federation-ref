// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.routing;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code OBJECT_VERSION_ID = object_id :: creating_system_id :: version_tree_id}
 * (spec §12.2). The middle segment is the follow-up routing key. Uids are never
 * mutated — this parser only reads.
 */
public final class VersionUidParser {

    private static final Pattern OBJECT_VERSION_ID =
            Pattern.compile("([^:]+)::([^:]+)::([0-9][0-9.]*)");

    private VersionUidParser() {
    }

    public record VersionUid(String objectId, String creatingSystemId, String versionTreeId) {
    }

    public static Optional<VersionUid> parse(final String uid) {
        if (uid == null) {
            return Optional.empty();
        }
        final Matcher m = OBJECT_VERSION_ID.matcher(uid);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new VersionUid(m.group(1), m.group(2), m.group(3)));
    }

    /** First OBJECT_VERSION_ID-shaped segment of a request path, if any. */
    public static Optional<VersionUid> findInPath(final String path) {
        for (final String segment : path.split("/")) {
            final Optional<VersionUid> parsed = parse(segment);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }
}
