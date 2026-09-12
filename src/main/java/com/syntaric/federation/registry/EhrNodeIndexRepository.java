// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;

public interface EhrNodeIndexRepository extends ListCrudRepository<EhrNodeIndexEntry, Long> {

    List<EhrNodeIndexEntry> findByEhrId(final String ehrId);

    Optional<EhrNodeIndexEntry> findByEhrIdAndNodeId(final String ehrId, final String nodeId);

    /** Reference count for the node delete policy (plan 3 §A1). */
    long countByNodeId(final String nodeId);
}
