// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.directive;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Result of stripping the federation directive from a façade AQL text.
 * {@code strippedAql} is standard, node-conformant AQL (modulo the subject
 * predicate, which the rewrite stage handles).
 */
public record PreprocessedAql(
        String strippedAql,
        boolean directivePresent,
        LinkedHashSet<String> endpointIds,
        LinkedHashSet<String> organisationIds,
        List<EndpointProjection> projections) {

    /** ENDPOINT attribute selected in the façade query, e.g. {@code p/id AS endpoint_id}. */
    public record EndpointProjection(Attribute attribute, String columnName, int position) {

        public enum Attribute {
            ID("id", "endpoint_id"),
            SYSTEM_ID("system_id", "system_id"),
            ORGANISATION("organisation", "organisation"),
            URL("url", "url");

            private final String path;
            private final String defaultColumnName;

            Attribute(final String path, final String defaultColumnName) {
                this.path = path;
                this.defaultColumnName = defaultColumnName;
            }

            public String path() {
                return path;
            }

            public String defaultColumnName() {
                return defaultColumnName;
            }
        }
    }

    public static PreprocessedAql withoutDirective(final String aql) {
        return new PreprocessedAql(aql, false, new LinkedHashSet<>(), new LinkedHashSet<>(), List.of());
    }
}
