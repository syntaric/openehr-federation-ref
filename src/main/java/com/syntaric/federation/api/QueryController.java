// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.FederatedQueryService;
import com.syntaric.federation.query.FederationHeaders;
import com.syntaric.federation.query.aql.merge.ResultEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST {base}/v1/query/aql} — the only fan-out operation in v1 (§7a.1).
 * Stored queries ({@code /v1/query/{name}}) are deferred and answer 501.
 */
@RestController
public class QueryController {

    private final FederatedQueryService queryService;

    public QueryController(final FederatedQueryService queryService) {
        this.queryService = queryService;
    }

    public record QueryRequest(String q, Map<String, Object> query_parameters) {
    }

    @PostMapping(path = "/v1/query/aql", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResultEnvelope> query(@RequestBody final QueryRequest body,
                                                final HttpServletRequest request) {
        final ResultEnvelope envelope = queryService.execute(
                body == null ? null : body.q(),
                RequestContexts.federation(request),
                request.getHeader("Authorization"));
        final String contributing = envelope.meta().endpoints().stream()
                .filter(e -> "active".equals(e.status()))
                .map(ResultEnvelope.EndpointMeta::id)
                .reduce((a, b) -> a + ", " + b).orElse(null);
        final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (contributing != null) {
            response.header(FederationHeaders.ENDPOINT, contributing);
        }
        return response.body(envelope);
    }

    @RequestMapping(path = "/v1/query/{qualifiedQueryName}/**")
    public ResponseEntity<Void> storedQuery() {
        throw new FederationException(FedErrorCode.FED_NOT_IMPLEMENTED,
                "Stored queries are not offered by this federation gateway in v1");
    }
}
