// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.FederationRequestContext;
import com.syntaric.federation.identity.spi.NodeAddressingService;
import com.syntaric.federation.outbound.proxy.ProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * N43/CP-34: every {@code /v1/definition/template/…} request routes to one
 * explicitly chosen node — never an implicit pick, never a merged template
 * catalogue. Fan-out template upload is not offered in v1 (declared in OPTIONS).
 *
 * <p>The mapping is the template sub-area only. ITS-REST's definition area has
 * two artefacts, templates and stored queries, and the second is held in this
 * gateway's own registry (§12.7, {@link StoredQueryDefinitionController}) rather
 * than routed. Nothing is lost by narrowing: there is no third sub-area.
 */
@RestController
public class DefinitionController {

    private final NodeAddressingService addressing;
    private final ProxyService proxy;

    public DefinitionController(final NodeAddressingService addressing, final ProxyService proxy) {
        this.addressing = addressing;
        this.proxy = proxy;
    }

    @RequestMapping({"/v1/definition/template", "/v1/definition/template/**"})
    public void route(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        final FederationRequestContext context = RequestContexts.federation(request);
        if (context.headerEndpointIds().size() != 1) {
            throw new FederationException(FedErrorCode.FED_NO_TARGET,
                    "Definition requests route to a single explicitly chosen node; name exactly "
                            + "one endpoint via the openEHR-federation-endpoint header (N43)");
        }
        final String endpointId = context.headerEndpointIds().iterator().next();
        final EndpointDescriptor target = addressing.byEndpointId(endpointId)
                .orElseThrow(() -> new FederationException(FedErrorCode.FED_UNKNOWN_TARGET,
                        "Endpoint '" + endpointId + "' is not a member of this federation"));
        proxy.forward(request, response, target, request.getRequestURI());
    }
}
