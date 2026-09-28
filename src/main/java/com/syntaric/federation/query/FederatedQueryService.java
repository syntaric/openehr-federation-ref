// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.aql.analysis.AggregatePolicy;
import com.syntaric.federation.query.aql.analysis.IdentifierHygieneGuard;
import com.syntaric.federation.query.aql.analysis.LimitOffsetPolicy;
import com.syntaric.federation.query.aql.analysis.SubjectAnalysis;
import com.syntaric.federation.query.aql.analysis.SubjectPredicateExtractor;
import com.syntaric.federation.query.aql.directive.FederationDirectiveParser;
import com.syntaric.federation.query.aql.directive.PreprocessedAql;
import com.syntaric.federation.query.aql.merge.FederatedResultAssembler;
import com.syntaric.federation.query.aql.merge.ResultEnvelope;
import com.syntaric.federation.query.aql.parameters.QueryParameterBinder;
import com.syntaric.federation.query.aql.rewrite.AqlRewriter;
import com.syntaric.federation.query.aql.rewrite.NodeQuerySpec;
import com.syntaric.federation.query.fanout.FanOutExecutor;
import com.syntaric.federation.query.fanout.NodeOutcome;
import com.syntaric.federation.query.fanout.TimeoutBudget;
import com.syntaric.federation.identity.IdentityResolutionPipeline;
import com.syntaric.federation.identity.NodeResolution;
import com.syntaric.federation.identity.spi.NodeAddressingService;
import com.syntaric.federation.identity.spi.PatientToken;
import com.syntaric.federation.registry.RegistryService;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.parser.AqlParseException;
import org.ehrbase.openehr.sdk.aql.parser.AqlQueryParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The full federated AQL pipeline (spec §7): directive strip → parse → bind
 * parameters → subject analysis → policies → resolution → rewrite → hygiene
 * gate → fan-out → completion decision → merge.
 *
 * <p>The same pipeline serves an ad-hoc {@code POST /v1/query/aql} and a stored
 * query invoked by name (§12.7): the registry supplies the text, the client the
 * parameters, and from {@link #execute(QueryInvocation, FederationRequestContext, String)}
 * onward nothing knows which it was — bar the {@code name} in the envelope.
 */
@Service
public class FederatedQueryService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FederatedQueryService.class);

    private final NodeAddressingService addressing;
    private final RegistryService registry;
    private final IdentityResolutionPipeline identity;
    private final FanOutExecutor fanOut;
    private final FederationProperties properties;

    public FederatedQueryService(final NodeAddressingService addressing,
                                 final RegistryService registry,
                                 final IdentityResolutionPipeline identity,
                                 final FanOutExecutor fanOut,
                                 final FederationProperties properties) {
        this.addressing = addressing;
        this.registry = registry;
        this.identity = identity;
        this.fanOut = fanOut;
        this.properties = properties;
    }

    /** Ad-hoc AQL without parameters. */
    public ResultEnvelope execute(final String facadeAql, final FederationRequestContext context,
                                  final String inboundAuthorization) {
        return execute(QueryInvocation.adHoc(facadeAql, Map.of()), context, inboundAuthorization);
    }

    public ResultEnvelope execute(final QueryInvocation invocation, final FederationRequestContext context,
                                  final String inboundAuthorization) {
        final String facadeAql = invocation.q();
        if (facadeAql == null || facadeAql.isBlank()) {
            throw new FederationException(FedErrorCode.FED_AQL_INVALID, "Missing AQL query text (q)");
        }
        if (facadeAql.contains(AqlRewriter.EHR_ID_PLACEHOLDER)) {
            throw new FederationException(FedErrorCode.FED_AQL_INVALID,
                    "Query contains the reserved placeholder literal");
        }

        final PreprocessedAql pre = FederationDirectiveParser.parse(facadeAql);
        final List<EndpointDescriptor> targets = resolveTargets(pre, context);
        final Map<String, EndpointDescriptor> endpointInfo = new LinkedHashMap<>();
        targets.forEach(t -> endpointInfo.put(t.endpointId(), t));

        final AqlQuery query = parse(pre.strippedAql());
        // Bind BEFORE subject analysis: a `$patient_id` on the subject path is
        // the subject predicate once its value is in, and must be rewritten to
        // an ehr_id scope like any literal (§12.7 "under the ordinary rules of §7").
        QueryParameterBinder.bind(query, invocation.parameters());
        applyPaging(query, invocation);

        final SubjectAnalysis analysis = SubjectPredicateExtractor.analyse(query);
        AggregatePolicy.check(query, targets.size());
        LimitOffsetPolicy.check(query, targets.size());

        final TimeoutBudget budget = TimeoutBudget.start(
                properties.timeouts().perNode(), properties.timeouts().overallBudget(),
                context.preferWait());

        final List<NodeOutcome> skipped = new ArrayList<>();
        final List<NodeQuerySpec> specs = new ArrayList<>();
        final String template = AqlRewriter.toDispatchTemplate(query, analysis);
        // Backstop: a parameter in a position the binder does not walk would
        // otherwise be dispatched as the literal text `$name` and fail at the
        // node with a message the client cannot act on.
        QueryParameterBinder.assertNoUnboundParameter(template);

        if (analysis.hasSubject()) {
            final String subjectId = analysis.subjectId();
            // §5.2 resolves "<patientId> with its issuing namespace": the one the
            // client supplied beside the identifier, else the deployment's.
            final String namespace = analysis.issuingNamespace() != null
                    ? analysis.issuingNamespace()
                    : properties.identity().defaultNamespace();
            final PatientToken token = new PatientToken(subjectId, namespace);
            for (final NodeResolution resolution : identity.resolve(token, targets)) {
                final EndpointDescriptor endpoint = endpointInfo.get(resolution.endpointId());
                switch (resolution.status()) {
                    case RESOLVED -> specs.add(buildSpec(endpoint, template,
                            resolution.ehrId(), List.of(subjectId)));
                    case NOT_RESOLVED -> skipped.add(NodeOutcome.skipped(resolution.endpointId(),
                            NodeOutcome.STATUS_NOT_RESOLVED,
                            reasonOr(resolution, "patient not resolved at this node")));
                    // A registry member localization did not name: §11.1
                    // `not-localized`. Never asked, and no decision was made
                    // about it — so not `excluded` (which records that some
                    // authority ruled it out) and not `consent-denied`. Being
                    // out of scope, it does not clear meta.federation.complete
                    // (§11.1 "What in scope means").
                    case NOT_LOCALIZED -> skipped.add(NodeOutcome.skipped(resolution.endpointId(),
                            NodeOutcome.STATUS_NOT_LOCALIZED,
                            reasonOr(resolution, "not named by localization")));
                    // A real consent decision (N27a pre-filter, or a cached
                    // one) — the only path that may claim this status.
                    case CONSENT_DENIED -> skipped.add(NodeOutcome.skipped(resolution.endpointId(),
                            NodeOutcome.STATUS_CONSENT_DENIED,
                            reasonOr(resolution, "consent denies inclusion")));
                }
            }
        } else {
            for (final EndpointDescriptor endpoint : targets) {
                IdentifierHygieneGuard.assertClean("AQL", template, List.of());
                specs.add(new NodeQuerySpec(endpoint.endpointId(), endpoint.nodeId(),
                        endpoint.url(), null, template));
            }
        }

        final List<NodeOutcome> executed = specs.isEmpty()
                ? List.of()
                : fanOut.execute(specs, endpointInfo, budget, inboundAuthorization);

        final Map<String, NodeOutcome> byEndpoint = new LinkedHashMap<>();
        executed.forEach(o -> byEndpoint.put(o.endpointId(), o));
        skipped.forEach(o -> byEndpoint.put(o.endpointId(), o));
        final List<NodeOutcome> outcomes = targets.stream()
                .map(t -> byEndpoint.get(t.endpointId()))
                .filter(o -> o != null)
                .toList();

        final FederatedResultAssembler.Assembly assembly = new FederatedResultAssembler.Assembly(
                facadeAql, query, outcomes, endpointInfo,
                pre.projections(), analysis.subjectProjections(), analysis.subjectId(),
                context.dedupMode(), budget, properties.timeouts().overallBudget().toMillis(),
                invocation.storedQueryName());

        // §11.4: decide before assembling, so a failing fan-out never merges rows
        // it will not return — and still carries the envelope that says why.
        switch (CompletionPolicy.decide(outcomes, context.completeness())) {
            case CompletionPolicy.Fail fail -> throw new IncompleteFederationException(
                    fail.code(), failureMessage(fail), FederatedResultAssembler.failureMeta(assembly));
            case CompletionPolicy.Succeed ignored -> {
                // fall through to assemble
            }
        }
        return FederatedResultAssembler.assemble(assembly);
    }

    private static AqlQuery parse(final String aql) {
        try {
            return AqlQueryParser.parse(aql);
        } catch (final AqlParseException e) {
            throw new FederationException(FedErrorCode.FED_AQL_INVALID,
                    "AQL could not be parsed: " + e.getMessage());
        }
    }

    /**
     * ITS-REST {@code fetch}/{@code offset} are the stored-query form of
     * {@code LIMIT}/{@code OFFSET}. They are applied to the AST — not spliced
     * into text — and only where the AQL carries neither, so that
     * {@link LimitOffsetPolicy} governs the result exactly as it governs an
     * inline clause. A definition that already pages and a request that pages
     * again are two answers to one question, and are refused.
     */
    private static void applyPaging(final AqlQuery query, final QueryInvocation invocation) {
        if (invocation.fetch() == null && invocation.offset() == null) {
            return;
        }
        if (query.getLimit() != null || query.getOffset() != null) {
            throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED,
                    "fetch/offset cannot be combined with a query that already carries LIMIT/OFFSET");
        }
        if (invocation.fetch() != null) {
            if (invocation.fetch() < 0) {
                throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED, "fetch must not be negative");
            }
            query.setLimit(invocation.fetch());
        }
        if (invocation.offset() != null) {
            if (invocation.offset() < 0) {
                throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED, "offset must not be negative");
            }
            query.setOffset(invocation.offset());
        }
    }

    private static String failureMessage(final CompletionPolicy.Fail fail) {
        if (fail.code() == FedErrorCode.FED_INCOMPLETE) {
            return "All-or-nothing completeness (the default) and the following in-scope nodes did not "
                    + "answer: " + String.join(", ", fail.unanswered())
                    + ". See meta.federation.endpoints[] for each node's status; re-issue with "
                    + FederationHeaders.COMPLETENESS + ": " + FederationHeaders.COMPLETENESS_PARTIAL
                    + " to accept the rows that did arrive.";
        }
        return "All-or-nothing completeness (the default) and the following in-scope nodes answered "
                + "with an error: " + String.join(", ", fail.erroring())
                + ". Each node's own status and message is in meta.federation.endpoints[].error; "
                + "re-issue with " + FederationHeaders.COMPLETENESS + ": "
                + FederationHeaders.COMPLETENESS_PARTIAL + " to accept the rows that did arrive.";
    }

    private NodeQuerySpec buildSpec(final EndpointDescriptor endpoint, final String template, final String ehrId,
                                    final List<String> forbiddenValues) {
        final String nodeAql = AqlRewriter.forNode(template, ehrId);
        final String url = endpoint.url() + "/v1/query/aql";
        IdentifierHygieneGuard.assertClean("AQL", nodeAql, forbiddenValues);
        IdentifierHygieneGuard.assertClean("URL", url, forbiddenValues);
        return new NodeQuerySpec(endpoint.endpointId(), endpoint.nodeId(), endpoint.url(), ehrId, nodeAql);
    }

    /** The pipeline's specific reason when it has one, else the generic status text. */
    private static String reasonOr(final NodeResolution resolution, final String fallback) {
        return resolution.reason() == null || resolution.reason().isBlank()
                ? fallback
                : resolution.reason();
    }

    /** N35/§8.4.1: directive vs header conflict handling and registry validation. */
    private List<EndpointDescriptor> resolveTargets(final PreprocessedAql pre, final FederationRequestContext context) {
        final LinkedHashSet<String> directiveSet = expand(pre.endpointIds(), pre.organisationIds());
        final LinkedHashSet<String> headerSet = expand(context.headerEndpointIds(), context.headerOrganisationIds());

        final boolean directivePresent = !directiveSet.isEmpty();
        final boolean headerPresent = !headerSet.isEmpty();
        if (directivePresent && headerPresent && !directiveSet.equals(headerSet)) {
            throw new FederationException(FedErrorCode.FED_TARGET_CONFLICT,
                    "FROM ENDPOINT and openEHR-federation-endpoint name different node sets",
                    Map.of("directive_set", List.copyOf(directiveSet),
                            "header_set", List.copyOf(headerSet)));
        }
        final LinkedHashSet<String> effective = directivePresent ? directiveSet : headerSet;
        if (effective.isEmpty()) {
            return addressing.activeMembers();
        }
        final List<EndpointDescriptor> result = new ArrayList<>();
        for (final String endpointId : effective) {
            result.add(addressing.byEndpointId(endpointId)
                    .orElseThrow(() -> new FederationException(FedErrorCode.FED_UNKNOWN_TARGET,
                            "Endpoint '" + endpointId + "' is not a member of this federation")));
        }
        return result;
    }

    /** Expands organisation selectors to endpoint ids and unions with explicit endpoints. */
    private LinkedHashSet<String> expand(final Set<String> endpointIds, final Set<String> organisationIds) {
        final LinkedHashSet<String> result = new LinkedHashSet<>(endpointIds);
        if (!organisationIds.isEmpty()) {
            for (final String orgId : organisationIds) {
                if (registry.organisation(orgId).isEmpty()) {
                    throw new FederationException(FedErrorCode.FED_UNKNOWN_TARGET,
                            "Organisation '" + orgId + "' is not a member of this federation");
                }
            }
            registry.endpointsForOrganisations(organisationIds).forEach(e -> result.add(e.endpointId()));
        }
        return result;
    }
}
