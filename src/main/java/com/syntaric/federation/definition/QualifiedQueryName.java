// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.definition;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;

import java.util.regex.Pattern;

/**
 * An ITS-REST qualified query name, {@code [{namespace}::]{query-name}}.
 *
 * <p>Both parts are restricted to the characters ITS-REST examples use —
 * letters, digits, {@code .}, {@code -} and {@code _} — so a name is always a
 * single clean path segment and can never carry a separator, a quote or
 * whitespace into a URL or an AQL error message.
 *
 * <p>{@code aql} is reserved: {@code POST {base}/v1/query/aql} is the ad-hoc
 * route (§7a.1), and a stored query of that name would be unreachable at best
 * and ambiguous at worst.
 */
public record QualifiedQueryName(String namespace, String name) {

    private static final Pattern PART = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    static final String RESERVED = "aql";

    public static QualifiedQueryName parse(final String qualified) {
        if (qualified == null || qualified.isBlank()) {
            throw invalid("Stored query name is empty");
        }
        final int separator = qualified.indexOf("::");
        final String namespace = separator < 0 ? null : qualified.substring(0, separator);
        final String name = separator < 0 ? qualified : qualified.substring(separator + 2);
        if (namespace != null && !PART.matcher(namespace).matches()) {
            throw invalid("Stored query namespace is malformed; expected [namespace::]name");
        }
        if (!PART.matcher(name).matches()) {
            throw invalid("Stored query name is malformed; expected [namespace::]name");
        }
        if (RESERVED.equalsIgnoreCase(qualified)) {
            throw invalid("'" + RESERVED + "' is the ad-hoc query route and cannot name a stored query");
        }
        return new QualifiedQueryName(namespace, name);
    }

    /** The wire form, as stored and as emitted in the envelope's {@code name}. */
    public String qualified() {
        return namespace == null ? name : namespace + "::" + name;
    }

    @Override
    public String toString() {
        return qualified();
    }

    private static FederationException invalid(final String message) {
        return new FederationException(FedErrorCode.FED_STORED_QUERY_INVALID, message);
    }
}
