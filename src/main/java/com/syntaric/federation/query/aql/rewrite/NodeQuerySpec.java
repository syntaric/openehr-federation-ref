// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.rewrite;

/**
 * One node's dispatchable query: the endpoint to call and the exact AQL wire
 * text (placeholder already substituted, hygiene-checked).
 */
public record NodeQuerySpec(
        String endpointId,
        String nodeId,
        String baseUrl,
        String resolvedEhrId,
        String aql) {
}
