// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.aql.merge.ResultEnvelope;

/**
 * A fan-out failed under the all-or-nothing default (§11.4): {@code 504} when an
 * in-scope node did not answer, {@code 424} when one answered with an error.
 *
 * <p>Carries the diagnostic envelope the failing response MUST still include —
 * {@code meta.federation.endpoints[]} with every in-scope node's status and
 * {@code complete: false} — so a client can see which node failed and why, and
 * decide whether to retry, route around it, or ask for {@code partial}. Of the
 * requirements in §11.4 this is the one the spec expects implementations to
 * drop; making it a constructor argument is how it does not get dropped here.
 */
public class IncompleteFederationException extends FederationException {

    private final transient ResultEnvelope.Meta meta;

    public IncompleteFederationException(final FedErrorCode code, final String message,
                                         final ResultEnvelope.Meta meta) {
        super(code, message);
        this.meta = meta;
    }

    @Override
    public Object meta() {
        return meta;
    }
}
