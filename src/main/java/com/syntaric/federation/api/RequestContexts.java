// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api;

import com.syntaric.federation.query.FederationRequestContext;
import jakarta.servlet.http.HttpServletRequest;

/** Small helpers shared by the /v1 controllers. */
final class RequestContexts {

    private RequestContexts() {
    }

    static FederationRequestContext federation(final HttpServletRequest request) {
        final Object attribute = request.getAttribute(FederationRequestContext.REQUEST_ATTRIBUTE);
        return attribute instanceof FederationRequestContext context
                ? context
                : FederationRequestContext.empty();
    }

}
