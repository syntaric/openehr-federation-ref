// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.definition.QualifiedQueryName;
import com.syntaric.federation.definition.SemVer;
import com.syntaric.federation.definition.StoredQueryMetadata;
import com.syntaric.federation.definition.StoredQueryService;
import com.syntaric.federation.query.aql.analysis.SubjectPredicateExtractor;
import com.syntaric.federation.query.aql.directive.FederationDirectiveParser;
import com.syntaric.federation.query.aql.rewrite.AqlRewriter;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.parser.AqlParseException;
import org.ehrbase.openehr.sdk.aql.parser.AqlQueryParser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * §12.7 / N44 / CP-40: the gateway-held stored-query registry's management
 * surface, on ITS-REST's own paths so a client that manages stored queries at a
 * CDR manages them here unchanged.
 *
 * <ul>
 *   <li>{@code PUT {base}/v1/definition/query/{name}/{version}} — store, once;
 *       a second PUT to the same pair is 409, never an overwrite;</li>
 *   <li>{@code GET {base}/v1/definition/query/{name}/{version}} — one version;</li>
 *   <li>{@code GET {base}/v1/definition/query/{name}} — every version.</li>
 * </ul>
 *
 * <p>Unlike {@code /v1/definition/template/**} (N43, one explicitly chosen
 * node), nothing here reaches a node: the gateway <em>is</em> the authority for
 * the definition, and executes it federated when it is invoked by name.
 *
 * <p>This route is as unauthenticated as every other on this gateway. A
 * deployment that lets clients query the federation but not define named
 * queries gates it at the edge, like the rest.
 */
@RestController
public class StoredQueryDefinitionController {

    private final StoredQueryService storedQueries;

    public StoredQueryDefinitionController(final StoredQueryService storedQueries) {
        this.storedQueries = storedQueries;
    }

    /** ITS-REST JSON form of the definition body. */
    public record DefinitionBody(String q) {
    }

    @PutMapping(path = "/v1/definition/query/{qualifiedQueryName}/{version}",
            consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoredQueryMetadata> storeText(@PathVariable final String qualifiedQueryName,
                                                         @PathVariable final String version,
                                                         @RequestBody(required = false) final String aql) {
        return store(qualifiedQueryName, version, aql);
    }

    @PutMapping(path = "/v1/definition/query/{qualifiedQueryName}/{version}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoredQueryMetadata> storeJson(@PathVariable final String qualifiedQueryName,
                                                         @PathVariable final String version,
                                                         @RequestBody(required = false) final DefinitionBody body) {
        return store(qualifiedQueryName, version, body == null ? null : body.q());
    }

    @GetMapping(path = "/v1/definition/query/{qualifiedQueryName}/{version}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoredQueryMetadata> get(@PathVariable final String qualifiedQueryName,
                                                   @PathVariable final String version) {
        final QualifiedQueryName name = QualifiedQueryName.parse(qualifiedQueryName);
        return ResponseEntity.ok(StoredQueryMetadata.of(storedQueries.require(name, SemVer.parse(version))));
    }

    /** ITS-REST list form: {@code {"versions": [ {name, type, version, saved, q}, … ]}}. */
    @GetMapping(path = "/v1/definition/query/{qualifiedQueryName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, List<StoredQueryMetadata>>> list(
            @PathVariable final String qualifiedQueryName) {
        final QualifiedQueryName name = QualifiedQueryName.parse(qualifiedQueryName);
        final List<StoredQueryMetadata> versions = storedQueries.versions(name);
        if (versions.isEmpty()) {
            throw new FederationException(FedErrorCode.FED_NOT_FOUND, "No stored query named '" + name + "'");
        }
        return ResponseEntity.ok(Map.of("versions", versions));
    }

    private ResponseEntity<StoredQueryMetadata> store(final String qualifiedQueryName, final String version,
                                                      final String aql) {
        final QualifiedQueryName name = QualifiedQueryName.parse(qualifiedQueryName);
        final SemVer semVer = SemVer.parse(version);
        validate(aql);
        return ResponseEntity.ok(storedQueries.store(name, semVer, aql.trim()));
    }

    /**
     * A definition must be AQL this gateway could federate — parseable after
     * the directive is stripped — and must not persist a patient identifier.
     *
     * <p>The second rule is the one with teeth. A stored query outlives the
     * request that stored it, so a literal on the subject path would put a
     * patient identifier at rest in a gateway N33 forbids to hold one; the
     * definition names the patient through a {@code $parameter} and the value
     * arrives per invocation. The same code that guards the wire guards the
     * registry, deliberately.
     */
    private static void validate(final String aql) {
        if (aql == null || aql.isBlank()) {
            throw new FederationException(FedErrorCode.FED_AQL_INVALID, "Missing AQL query text");
        }
        if (aql.contains(AqlRewriter.EHR_ID_PLACEHOLDER)) {
            throw new FederationException(FedErrorCode.FED_AQL_INVALID,
                    "Query contains the reserved placeholder literal");
        }
        final AqlQuery query;
        try {
            query = AqlQueryParser.parse(FederationDirectiveParser.parse(aql).strippedAql());
        } catch (final AqlParseException e) {
            throw new FederationException(FedErrorCode.FED_AQL_INVALID,
                    "AQL could not be parsed: " + e.getMessage());
        }
        final List<String> literalSubjects = SubjectPredicateExtractor.literalSubjectPaths(query);
        if (!literalSubjects.isEmpty()) {
            throw new FederationException(FedErrorCode.FED_IDENTIFIER_HYGIENE,
                    "A stored query must name the patient through a $parameter, never a literal: "
                            + "the gateway does not persist patient identifiers (N33)",
                    Map.of("paths", literalSubjects));
        }
    }
}
