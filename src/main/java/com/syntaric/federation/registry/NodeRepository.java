// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;

public interface NodeRepository extends ListCrudRepository<Node, String> {

    Optional<Node> findBySystemId(final String systemId);

    List<Node> findByOrganisationId(final String organisationId);
}
