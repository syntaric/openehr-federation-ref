// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.definition;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One immutable version of a gateway-held stored query (§12.7, N44).
 *
 * <p>Column order matches component order; Spring Data JDBC binds by name but
 * every construction site is positional, so the two are kept in step.
 */
@Table("stored_query")
public record StoredQuery(
        @Id Long id,
        String name,
        String version,
        String type,
        String aql,
        Instant savedAt) {

    public static final String TYPE_AQL = "AQL";

    /** A definition about to be inserted: no id yet, saved now. */
    public static StoredQuery of(final String name, final String version, final String aql, final Instant savedAt) {
        return new StoredQuery(null, name, version, TYPE_AQL, aql, savedAt);
    }
}
