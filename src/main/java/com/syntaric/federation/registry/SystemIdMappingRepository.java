// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface SystemIdMappingRepository extends ListCrudRepository<SystemIdMapping, String> {

    List<SystemIdMapping> findByNodeId(final String nodeId);

    /** Reference count for the node delete policy (plan 3 §A1). */
    long countByNodeId(final String nodeId);
}
