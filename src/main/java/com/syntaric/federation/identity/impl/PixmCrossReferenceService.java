// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.identity.spi.PatientCrossReferenceService;
import com.syntaric.federation.identity.spi.PatientToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;

/**
 * IHE PIXm {@code $ihe-pix} binding over plain {@link RestClient} — two small
 * Jackson records instead of a HAPI FHIR dependency (plan decision #5). The
 * per-endpoint PIX manager URL comes from the registry
 * ({@code endpoint.pix_manager_url}). Active when {@code federation.identity.mode=pixm}.
 *
 * <p>The target system for the returned identifier is the node's ehr_id
 * namespace, conventionally {@code <baseUrl>/ehr-id} unless configured.
 */
@Component
@ConditionalOnProperty(name = "federation.identity.mode", havingValue = "pixm")
public class PixmCrossReferenceService implements PatientCrossReferenceService {

    private static final Logger log = LoggerFactory.getLogger(PixmCrossReferenceService.class);

    private final RestClient restClient;

    public PixmCrossReferenceService() {
        this.restClient = RestClient.create();
    }

    @Override
    public Optional<String> resolveEhrId(final PatientToken token, final EndpointDescriptor endpoint) {
        final String pixManagerUrl = pixManagerUrl(endpoint);
        if (pixManagerUrl == null) {
            return Optional.empty();
        }
        try {
            final String uri = UriComponentsBuilder.fromUriString(pixManagerUrl)
                    .pathSegment("Patient", "$ihe-pix")
                    .queryParam("sourceIdentifier", token.system() + "|" + token.value())
                    .queryParam("targetSystem", endpoint.url() + "/ehr-id")
                    .build().toUriString();
            final Parameters parameters = restClient.get().uri(uri).retrieve().body(Parameters.class);
            if (parameters == null || parameters.parameter() == null) {
                return Optional.empty();
            }
            return parameters.parameter().stream()
                    .filter(p -> "targetIdentifier".equals(p.name()) && p.valueIdentifier() != null)
                    .map(p -> p.valueIdentifier().value())
                    .findFirst();
        } catch (final Exception e) {
            log.warn("PIXm resolution failed for endpoint {}: {}", endpoint.endpointId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String pixManagerUrl(final EndpointDescriptor endpoint) {
        return endpoint.pixManagerUrl();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parameters(List<Parameter> parameter) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parameter(String name, Identifier valueIdentifier) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Identifier(String system, String value) {
    }
}
