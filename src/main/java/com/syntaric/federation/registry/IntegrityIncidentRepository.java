// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface IntegrityIncidentRepository extends ListCrudRepository<IntegrityIncident, Long> {

    List<IntegrityIncident> findByType(final String type);
}
