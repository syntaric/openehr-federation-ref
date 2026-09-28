// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.analysis;

import org.ehrbase.openehr.sdk.aql.dto.condition.ComparisonOperatorCondition;
import org.ehrbase.openehr.sdk.aql.dto.containment.ContainmentClassExpression;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;

import java.util.List;

/**
 * Outcome of analysing the (directive-stripped) façade AQL: where the subject
 * predicate sits, which carrier the client used to name the patient (§5.4.3),
 * which SELECT items project the subject, and the EHR variable to scope
 * per-node dispatch on. Node references point into the analysed
 * {@code AqlQuery} instance so the rewriter can mutate them in place.
 */
public record SubjectAnalysis(
        /** Raw resolution input (the directly identifying identifier), or null. */
        String subjectId,
        /**
         * The issuing namespace the client supplied alongside the identifier
         * (§5.2), or null when it supplied none and the deployment default
         * applies. Comes from {@code external_ref/namespace} for the canonical
         * carrier and from {@code identifiers/issuer} (falling back to
         * {@code identifiers/type}) for an ENTRY-level subject.
         */
        String issuingNamespace,
        /** Which carrier named the patient; null when the query has no subject. */
        Carrier carrier,
        /** The EHR containment the query is scoped on. */
        ContainmentClassExpression ehrContainment,
        /**
         * WHERE equalities on the patient identifier — either carrier — each of
         * which the rewriter replaces with the {@code ehr_id} predicate.
         */
        List<ComparisonOperatorCondition> subjectPredicates,
        /**
         * WHERE equalities on the identifier's namespace, consumed by resolution
         * and removed from the dispatched query: a node is not required to hold
         * the identifier at all (§5.2), so a namespace predicate left in place
         * would filter on data the node may not have.
         */
        List<ComparisonOperatorCondition> namespacePredicates,
        /** SELECT items projecting the subject path, to be stripped and re-injected. */
        List<SubjectProjection> subjectProjections) {

    /**
     * The two resolution inputs §5.4.3 makes mandatory. A query that names the
     * patient through both — with the same value — is accepted and reported as
     * {@link #EXTERNAL_REF}, the canonical form.
     */
    public enum Carrier {
        /** {@code EHR_STATUS.subject.external_ref.id.value} rooted at the EHR (§7.1). */
        EXTERNAL_REF,
        /** An {@code ENTRY}-level {@code subject} {@code PARTY_IDENTIFIED}/{@code DV_IDENTIFIER} (§5.4.2). */
        ENTRY_SUBJECT
    }

    public record SubjectProjection(SelectExpression expression, String columnName, int position) {
    }

    public boolean hasSubject() {
        return subjectId != null;
    }
}
