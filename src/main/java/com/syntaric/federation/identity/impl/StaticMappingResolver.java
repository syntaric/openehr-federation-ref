// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.impl;

import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.identity.spi.PatientCrossReferenceService;
import com.syntaric.federation.identity.spi.PatientToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Dev/test cross-reference: {@code federation.identity.static-mappings} maps a
 * patient identifier value to per-endpoint ehr_ids. Active when
 * {@code federation.identity.mode=static}.
 */
@Component
@ConditionalOnProperty(name = "federation.identity.mode", havingValue = "static", matchIfMissing = true)
public class StaticMappingResolver implements PatientCrossReferenceService {

    private final FederationProperties properties;

    public StaticMappingResolver(final FederationProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> resolveEhrId(final PatientToken token, final EndpointDescriptor endpoint) {
        final Map<String, String> perEndpoint = properties.identity().staticMappings().get(token.value());
        if (perEndpoint == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(perEndpoint.get(endpoint.endpointId()));
    }
}
