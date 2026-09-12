// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.rewrite;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.aql.analysis.SubjectAnalysis;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.dto.condition.ComparisonOperatorCondition;
import org.ehrbase.openehr.sdk.aql.dto.condition.ComparisonOperatorSymbol;
import org.ehrbase.openehr.sdk.aql.dto.operand.IdentifiedPath;
import org.ehrbase.openehr.sdk.aql.dto.operand.StringPrimitive;
import org.ehrbase.openehr.sdk.aql.dto.path.AqlObjectPath;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;
import org.ehrbase.openehr.sdk.aql.render.AqlRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AST surgery confined to one shape (plan decision #1): every canonical subject
 * predicate becomes {@code e/ehr_id/value = '$$FEDERATION_EHR_ID$$'}, subject
 * projections are removed, and the query is rendered ONCE. Per-node dispatch is
 * a plain string substitution of the placeholder with the resolved ehr_id — a
 * gateway-controlled UUID, so the substitution cannot inject syntax.
 */
public final class AqlRewriter {

    public static final String EHR_ID_PLACEHOLDER = "$$FEDERATION_EHR_ID$$";

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private AqlRewriter() {
    }

    /** Renders the per-node dispatch template. Mutates {@code query} in place. */
    public static String toDispatchTemplate(final AqlQuery query, final SubjectAnalysis analysis) {
        if (!analysis.hasSubject()) {
            return AqlRenderer.render(query);
        }
        for (final ComparisonOperatorCondition predicate : analysis.subjectPredicates()) {
            final IdentifiedPath ehrIdPath = new IdentifiedPath();
            ehrIdPath.setRoot(analysis.ehrContainment());
            ehrIdPath.setPath(AqlObjectPath.parse("ehr_id/value"));
            predicate.setStatement(ehrIdPath);
            predicate.setSymbol(ComparisonOperatorSymbol.EQ);
            predicate.setValue(new StringPrimitive(EHR_ID_PLACEHOLDER));
        }
        if (!analysis.subjectProjections().isEmpty()) {
            final List<SelectExpression> remaining = new ArrayList<>(query.getSelect().getStatement());
            analysis.subjectProjections().forEach(p -> remaining.remove(p.expression()));
            if (remaining.isEmpty()) {
                throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED,
                        "SELECT must include at least one non-subject column");
            }
            query.getSelect().setStatement(remaining);
        }
        final String template = AqlRenderer.render(query);
        if (!template.contains(EHR_ID_PLACEHOLDER)) {
            throw new FederationException(FedErrorCode.FED_IDENTIFIER_UNSTRIPPABLE,
                    "Subject predicate could not be rewritten to an ehr_id scope");
        }
        return template;
    }

    /** Substitutes the resolved ehr_id for one node. Fails closed on non-UUID ids. */
    public static String forNode(final String template, final String resolvedEhrId) {
        if (resolvedEhrId == null || !UUID_PATTERN.matcher(resolvedEhrId).matches()) {
            throw new FederationException(FedErrorCode.FED_QUERY_UNSUPPORTED,
                    "Resolved ehr_id is not a UUID; refusing dispatch");
        }
        return template.replace(EHR_ID_PLACEHOLDER, resolvedEhrId);
    }
}
