// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.spi;

import com.syntaric.federation.query.EndpointDescriptor;

import java.util.Optional;

/**
 * N3: exchanges a patient identifier for the node-local {@code ehr_id},
 * outside AQL. Proposed spec binding is IHE PIXm ({@code $ihe-pix}); the wire
 * details are deployment-defined, hence an SPI.
 */
public interface PatientCrossReferenceService {

    /** The node-local ehr_id for the patient at this endpoint, if any. */
    Optional<String> resolveEhrId(final PatientToken token, final EndpointDescriptor endpoint);
}
