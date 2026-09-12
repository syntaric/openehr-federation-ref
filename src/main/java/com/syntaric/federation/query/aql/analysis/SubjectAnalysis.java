// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.analysis;

import org.ehrbase.openehr.sdk.aql.dto.condition.ComparisonOperatorCondition;
import org.ehrbase.openehr.sdk.aql.dto.containment.ContainmentClassExpression;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;

import java.util.List;

/**
 * Outcome of analysing the (directive-stripped) façade AQL: where the subject
 * predicate sits, which SELECT items project the subject, and the EHR variable
 * to scope per-node dispatch on. Node references point into the analysed
 * {@code AqlQuery} instance so the rewriter can mutate them in place.
 */
public record SubjectAnalysis(
        /** Raw resolution input (the directly identifying identifier), or null. */
        String subjectId,
        /** The EHR containment the query is scoped on. */
        ContainmentClassExpression ehrContainment,
        /** WHERE comparisons of the canonical subject form, to be replaced. */
        List<ComparisonOperatorCondition> subjectPredicates,
        /** SELECT items projecting the subject path, to be stripped and re-injected. */
        List<SubjectProjection> subjectProjections) {

    public record SubjectProjection(SelectExpression expression, String columnName, int position) {
    }

    public boolean hasSubject() {
        return subjectId != null;
    }
}
