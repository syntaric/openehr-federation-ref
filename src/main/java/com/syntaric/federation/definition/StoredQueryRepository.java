// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.definition;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;

public interface StoredQueryRepository extends ListCrudRepository<StoredQuery, Long> {

    List<StoredQuery> findByName(final String name);

    Optional<StoredQuery> findByNameAndVersion(final String name, final String version);
}
