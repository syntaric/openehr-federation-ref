// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import java.util.Map;

/**
 * One request to run a query through the federation engine, whether the client
 * sent the AQL inline ({@code POST /v1/query/aql}) or named a stored definition
 * ({@code /v1/query/{name}}, §12.7). The engine treats the two identically
 * after this point — which is exactly what N44 asks for.
 *
 * @param q               the façade AQL text, with {@code $parameters} unbound;
 *                        echoed as the envelope's {@code q}
 * @param parameters      ITS-REST {@code query_parameters}; bound into the AST
 *                        before subject analysis so a {@code $patient_id} is
 *                        seen as the subject it is
 * @param storedQueryName the {@code [namespace::]name} this answers under, or
 *                        null for ad-hoc AQL — becomes the envelope's {@code name}
 * @param fetch           ITS-REST {@code fetch}: a LIMIT the AQL itself does not
 *                        carry, or null
 * @param offset          ITS-REST {@code offset}: an OFFSET the AQL itself does
 *                        not carry, or null; governed by the same N39 policy as
 *                        an inline OFFSET
 */
public record QueryInvocation(
        String q,
        Map<String, Object> parameters,
        String storedQueryName,
        Long fetch,
        Long offset) {

    public QueryInvocation {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public static QueryInvocation adHoc(final String q, final Map<String, Object> parameters) {
        return new QueryInvocation(q, parameters, null, null, null);
    }
}
