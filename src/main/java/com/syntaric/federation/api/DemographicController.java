// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** N32/CP-25: the DEMOGRAPHIC API is never federated; this deployment answers 501. */
@RestController
public class DemographicController {

    @RequestMapping("/v1/demographic/**")
    public void demographic() {
        throw new FederationException(FedErrorCode.FED_NOT_IMPLEMENTED,
                "The DEMOGRAPHIC API is not federated (N32); this gateway does not expose it");
    }
}
