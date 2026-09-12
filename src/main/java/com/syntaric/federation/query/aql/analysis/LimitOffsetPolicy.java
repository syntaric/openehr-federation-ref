// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.aql.analysis;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;

/**
 * N39/§11.6.2: this gateway's choice for {@code OFFSET > 0} is rejection (declared as
 * {@code "offset": "reject"} in the OPTIONS self-description). {@code LIMIT n}
 * is dispatched per node and re-applied across the merged rows.
 */
public final class LimitOffsetPolicy {

    private LimitOffsetPolicy() {
    }

    public static void check(final AqlQuery query, final int targetNodeCount) {
        final Long offset = query.getOffset();
        if (offset != null && offset > 0 && targetNodeCount > 1) {
            throw new FederationException(FedErrorCode.FED_OFFSET_UNSUPPORTED,
                    "Offset-based paging is not supported across a fan-out; "
                            + "re-issue without OFFSET or direct the query to a single node");
        }
    }
}
