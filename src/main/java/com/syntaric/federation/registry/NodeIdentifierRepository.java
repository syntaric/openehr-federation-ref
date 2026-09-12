// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface NodeIdentifierRepository extends ListCrudRepository<NodeIdentifier, Long> {

    /**
     * The clinical-path lookup: a localizer returned this identifier — who owns
     * it? Returns a list because nothing in the schema forbids two nodes
     * claiming one identifier; a federation in that state is misconfigured, and
     * the caller reports it rather than silently picking one.
     */
    List<NodeIdentifier> findBySystemAndValue(final String system, final String value);

    List<NodeIdentifier> findByNodeId(final String nodeId);

    /** Reference count for the node delete policy (plan 3 §A1). */
    long countByNodeId(final String nodeId);
}
