// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface EndpointRepository extends ListCrudRepository<Endpoint, String> {

    List<Endpoint> findByNodeId(final String nodeId);

    List<Endpoint> findByStatus(final String status);
}
