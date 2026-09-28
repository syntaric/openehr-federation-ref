// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.definition.QualifiedQueryName;
import com.syntaric.federation.definition.SemVer;
import com.syntaric.federation.definition.StoredQuery;
import com.syntaric.federation.definition.StoredQueryService;
import com.syntaric.federation.query.FederatedQueryService;
import com.syntaric.federation.query.FederationHeaders;
import com.syntaric.federation.query.QueryInvocation;
import com.syntaric.federation.query.aql.merge.ResultEnvelope;
import com.syntaric.federation.query.aql.parameters.QueryParameterBinder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The ITS-REST query area (§7a.1):
 * <ul>
 *   <li>{@code POST {base}/v1/query/aql} — ad-hoc AQL, the fan-out operation;</li>
 *   <li>{@code GET|POST {base}/v1/query/{name}[/{version}]} — a stored query held
 *       in this gateway's registry (§12.7, N44), expanded into the same fan-out
 *       with the client's {@code query_parameters} bound in.</li>
 * </ul>
 *
 * <p>The two share one engine call and one response path, so a stored query
 * answers exactly as the same text sent inline would — plus the ITS-REST
 * {@code name} member, which N44 requires so a client can see which definition
 * answered.
 */
@RestController
public class QueryController {

    /** ITS-REST reserves these on the GET form; everything else is a query parameter. */
    private static final Set<String> PAGING_PARAMETERS = Set.of("offset", "fetch");

    private final FederatedQueryService queryService;
    private final StoredQueryService storedQueries;

    public QueryController(final FederatedQueryService queryService, final StoredQueryService storedQueries) {
        this.queryService = queryService;
        this.storedQueries = storedQueries;
    }

    /** ITS-REST ad-hoc body: {@code {q, query_parameters}}. {@code offset}/{@code fetch} belong in the AQL. */
    public record QueryRequest(String q, Map<String, Object> query_parameters) {
    }

    /** ITS-REST stored-query body: {@code {query_parameters, offset, fetch}}. */
    public record StoredQueryRequest(Map<String, Object> query_parameters, Long offset, Long fetch) {
    }

    @PostMapping(path = "/v1/query/aql", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResultEnvelope> query(@RequestBody final QueryRequest body,
                                                final HttpServletRequest request) {
        final QueryInvocation invocation = QueryInvocation.adHoc(
                body == null ? null : body.q(),
                body == null ? Map.of() : body.query_parameters());
        return respond(queryService.execute(invocation, RequestContexts.federation(request),
                request.getHeader("Authorization")));
    }

    /** ITS-REST defines {@code GET /query/aql?q=}; this gateway offers the POST form only. */
    @GetMapping("/v1/query/aql")
    public ResponseEntity<Void> adHocGet() {
        throw new FederationException(FedErrorCode.FED_NOT_IMPLEMENTED,
                "Ad-hoc AQL is accepted as POST /v1/query/aql only");
    }

    @GetMapping(path = "/v1/query/{qualifiedQueryName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResultEnvelope> storedLatestGet(@PathVariable final String qualifiedQueryName,
                                                          final HttpServletRequest request) {
        return storedGet(qualifiedQueryName, null, request);
    }

    /**
     * The GET form: every query parameter other than {@code offset}/{@code fetch}
     * is an AQL parameter, typed by inference since a query string has none. A
     * repeated parameter is refused rather than silently reduced to one value.
     */
    @GetMapping(path = "/v1/query/{qualifiedQueryName}/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResultEnvelope> storedGet(@PathVariable final String qualifiedQueryName,
                                                    @PathVariable final String version,
                                                    final HttpServletRequest request) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        Long offset = null;
        Long fetch = null;
        for (final Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (entry.getValue().length != 1) {
                throw new FederationException(FedErrorCode.FED_QUERY_PARAMETER_INVALID,
                        "Query parameter supplied more than once", Map.of("parameter", entry.getKey()));
            }
            final String value = entry.getValue()[0];
            switch (entry.getKey()) {
                case "offset" -> offset = parsePaging("offset", value);
                case "fetch" -> fetch = parsePaging("fetch", value);
                default -> parameters.put(entry.getKey(), QueryParameterBinder.inferFromString(value));
            }
        }
        return stored(qualifiedQueryName, version, parameters, offset, fetch, request);
    }

    @PostMapping(path = "/v1/query/{qualifiedQueryName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResultEnvelope> storedLatestPost(@PathVariable final String qualifiedQueryName,
                                                           @RequestBody(required = false) final StoredQueryRequest body,
                                                           final HttpServletRequest request) {
        return storedPost(qualifiedQueryName, null, body, request);
    }

    @PostMapping(path = "/v1/query/{qualifiedQueryName}/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResultEnvelope> storedPost(@PathVariable final String qualifiedQueryName,
                                                     @PathVariable final String version,
                                                     @RequestBody(required = false) final StoredQueryRequest body,
                                                     final HttpServletRequest request) {
        return stored(qualifiedQueryName, version,
                body == null || body.query_parameters() == null ? Map.of() : body.query_parameters(),
                body == null ? null : body.offset(),
                body == null ? null : body.fetch(),
                request);
    }

    private ResponseEntity<ResultEnvelope> stored(final String qualifiedQueryName, final String version,
                                                  final Map<String, Object> parameters,
                                                  final Long offset, final Long fetch,
                                                  final HttpServletRequest request) {
        final QualifiedQueryName name = QualifiedQueryName.parse(qualifiedQueryName);
        final StoredQuery definition = storedQueries.require(name, version == null ? null : SemVer.parse(version));
        final QueryInvocation invocation = new QueryInvocation(
                definition.aql(), parameters, name.qualified(), fetch, offset);
        return respond(queryService.execute(invocation, RequestContexts.federation(request),
                request.getHeader("Authorization")));
    }

    private static Long parsePaging(final String name, final String value) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED, name + " must be an integer");
        }
    }

    /** §7a.3: the contributing endpoints also travel as a header, though {@code meta} stays normative. */
    private static ResponseEntity<ResultEnvelope> respond(final ResultEnvelope envelope) {
        final String contributing = envelope.meta().federation().endpoints().stream()
                .filter(e -> "active".equals(e.status()))
                .map(ResultEnvelope.EndpointMeta::id)
                .reduce((a, b) -> a + ", " + b).orElse(null);
        final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (contributing != null) {
            response.header(FederationHeaders.ENDPOINT, contributing);
        }
        return response.body(envelope);
    }
}
