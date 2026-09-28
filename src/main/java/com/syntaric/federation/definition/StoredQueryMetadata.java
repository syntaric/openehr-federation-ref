// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.definition;

import java.time.Instant;

/**
 * The ITS-REST stored-query description a definition route answers with:
 * {@code {name, type, version, saved, q}}. Wire keys named to match ITS-REST
 * exactly, since a client that manages stored queries at a single CDR is meant
 * to manage them here without noticing the difference (N1).
 */
public record StoredQueryMetadata(String name, String type, String version, Instant saved, String q) {

    public static StoredQueryMetadata of(final StoredQuery stored) {
        return new StoredQueryMetadata(stored.name(), stored.type(), stored.version(), stored.savedAt(), stored.aql());
    }
}
