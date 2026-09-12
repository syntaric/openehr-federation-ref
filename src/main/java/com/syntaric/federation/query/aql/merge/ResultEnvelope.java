// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.merge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Federated query result envelope (spec §9).
 *
 * <p>This <em>is</em> an openEHR ITS-REST {@code RESULT_SET} (Release-1.1.0), not
 * a federation-specific structure that resembles one — which is the mechanism by
 * which N1's transparency claim holds on the wire. The federation's additions
 * live in {@code meta}, whose ITS-REST schema declares
 * {@code additionalProperties: true} and is therefore an extension point by
 * design (§9.1).
 *
 * <p><strong>Rows are ordered arrays of values, not objects.</strong> ITS-REST
 * defines {@code ResultSetRow} as a JSON array whose <em>i</em>-th element is the
 * value of {@code columns[i]}; {@code columns[]} is the only thing that names
 * them. Before spec 0.4.0 this record emitted objects keyed by column name,
 * matching an example in §9.3 that turned out to contradict the standard it
 * claimed conformance to. The spec example was the thing that was wrong; see
 * {@code changes-from-2025-08-20.adoc#its-rest-alignment} finding F3.
 *
 * <p>Note the asymmetry with {@code meta}: rows are a <em>positional</em>
 * structure and {@code meta} is a named one, so the assembler keeps rows keyed by
 * column name internally (DISTINCT, ORDER BY and dedup all need to address values
 * by name) and flattens to arrays only here, at the wire boundary.
 *
 * <p>Wire keys are snake_case, named explicitly so serialization is identical
 * under Jackson 2 and Jackson 3.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultEnvelope(
        String q,
        List<Column> columns,
        List<List<Object>> rows,
        Meta meta) {

    /**
     * An ITS-REST {@code RESULT_SET_COLUMN}: {@code name} required, {@code path}
     * optional, and <em>no other members</em>. ITS-REST defines no {@code type},
     * so this record carries none — a gateway conveying a type does so in the row
     * value itself, as openEHR's own {@code {"_type": "DV_TEXT", ...}} form (§9.4).
     *
     * <p>{@code path} is always the gateway's own rendering of the client's AQL,
     * never a node's (§9.2, N17, CP-35).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Column(String name, String path) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(
            boolean complete,
            List<EndpointMeta> endpoints,
            Timeout timeout,
            Dedup dedup) {
    }

    /** One entry per in-scope endpoint (spec §9.4); the normative coverage carrier. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EndpointMeta(
            String id,
            String status,
            String error,
            @JsonProperty("latency_ms") Long latencyMs,
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("system_id") String systemId,
            String organisation,
            String product,
            String version,
            @JsonProperty("row_count") Integer rowCount,
            String url) {
    }

    public record Timeout(
            @JsonProperty("per_node_ms") long perNodeMs,
            @JsonProperty("overall_ms") long overallMs,
            @JsonProperty("effective_overall_ms") long effectiveOverallMs) {
    }

    public record Dedup(String mode, List<Suppressed> suppressed) {

        public record Suppressed(
                @JsonProperty("object_id") String objectId,
                @JsonProperty("endpoint_id") String endpointId) {
        }
    }
}
