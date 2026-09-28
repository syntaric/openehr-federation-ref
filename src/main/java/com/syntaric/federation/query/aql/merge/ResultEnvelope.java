// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.merge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * <p><strong>Everything the federation adds sits under one member,
 * {@code meta.federation}.</strong> Releases up to spec 0.9.0 carried
 * {@code complete}, {@code endpoints}, {@code timeout} and {@code dedup} flat on
 * {@code meta}; the SEC amendments nested them so a reader can tell at a glance
 * which members the federation owns and a future openEHR {@code meta} member
 * cannot collide with one of them. The flat form is now a CP-35 failure. The
 * object is unprefixed — the {@code _} prefix stays reserved to openEHR (N17).
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
 * <p>{@code name} is ITS-REST's fifth top-level member: the
 * {@code [{namespace}::]{query-name}} of a stored query, emitted when the query
 * was invoked by name and naming the gateway's own definition, never a node's
 * (§9.1, §12.7, N44). It is null — and therefore omitted — for ad-hoc AQL.
 *
 * <p>Wire keys are snake_case, named explicitly so serialization is identical
 * under Jackson 2 and Jackson 3.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultEnvelope(
        String name,
        String q,
        List<Column> columns,
        List<List<Object>> rows,
        Meta meta) {

    /** The same envelope answering under a stored-query name (§12.7). */
    public ResultEnvelope withName(final String queryName) {
        return new ResultEnvelope(queryName, q, columns, rows, meta);
    }

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

    /**
     * ITS-REST {@code ResultSetMetadata}. The federation contributes exactly one
     * member here, {@code federation}; openEHR's own {@code _}-prefixed members
     * are not emitted by this gateway.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(FederationMeta federation) {
    }

    /**
     * {@code meta.federation} (§9.1): the coverage flag, the per-endpoint
     * provenance, the timeout budget in force and, when dedup ran, what it
     * suppressed. Open on the wire like {@code meta} itself.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FederationMeta(
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

    /**
     * {@code meta.federation.dedup} (§10.3, N36). The published schema names
     * {@code mode}, {@code suppressed_rows} and {@code suppressed_endpoints[]};
     * {@code suppressed[]} is this gateway's finer-grained per-row record, kept
     * because the schema leaves the object open and a client auditing a specific
     * suppression wants the {@code object_id}, not just the endpoint.
     */
    public record Dedup(
            String mode,
            @JsonProperty("suppressed_rows") int suppressedRows,
            @JsonProperty("suppressed_endpoints") List<String> suppressedEndpoints,
            List<Suppressed> suppressed) {

        /** Derives the two schema members from the per-row record: distinct endpoint ids, first-seen order. */
        public static Dedup of(final String mode, final List<Suppressed> suppressed) {
            final Set<String> endpoints = new LinkedHashSet<>();
            for (final Suppressed entry : suppressed) {
                endpoints.add(entry.endpointId());
            }
            return new Dedup(mode, suppressed.size(), List.copyOf(endpoints), suppressed);
        }

        public record Suppressed(
                @JsonProperty("object_id") String objectId,
                @JsonProperty("endpoint_id") String endpointId) {
        }
    }
}
