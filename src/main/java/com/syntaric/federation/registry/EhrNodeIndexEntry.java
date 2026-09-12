// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("ehr_node_index")
public record EhrNodeIndexEntry(
        @Id Long id,
        String ehrId,
        String nodeId,
        Instant observedAt) {
}
