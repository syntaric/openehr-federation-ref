// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;

public interface ResolutionBindingRepository extends ListCrudRepository<ResolutionBinding, Long> {

    List<ResolutionBinding> findByPatientRefHash(final String patientRefHash);

    Optional<ResolutionBinding> findByPatientRefHashAndNodeId(final String patientRefHash, final String nodeId);

    List<ResolutionBinding> findByLocalEhrId(final String localEhrId);

    /** Reference count for the node delete policy (plan 3 §A1). */
    long countByNodeId(final String nodeId);
}
