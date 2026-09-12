// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.analysis;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;

/**
 * The dumb, final, string-level N33 gate. Independent of the AST rewrite: it
 * scans the exact wire form of everything the gateway composed — the dispatched
 * AQL, the path, the query string and the headers — for any raw identifier
 * value consumed during resolution (including its URL-encoded form). A hit
 * means a gateway bug; the request fails closed with 400 and the value never
 * leaves the gateway. The identifier value itself is never put in the message.
 */
public final class IdentifierHygieneGuard {

    private IdentifierHygieneGuard() {
    }

    public static void assertClean(final String position, final String composed, final Collection<String> forbiddenValues) {
        if (composed == null || composed.isEmpty()) {
            return;
        }
        final String haystack = composed.toLowerCase(Locale.ROOT);
        for (final String value : forbiddenValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            final String needle = value.toLowerCase(Locale.ROOT);
            final String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (haystack.contains(needle) || haystack.contains(encoded)) {
                throw new FederationException(FedErrorCode.FED_IDENTIFIER_HYGIENE,
                        "A directly identifying identifier would reach a node via the composed "
                                + position + "; the query was not dispatched (N33)");
            }
        }
    }
}
