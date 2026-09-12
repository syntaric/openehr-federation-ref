// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("node")
public record Node(
        @Id String nodeId,
        String systemId,
        String organisationId,
        String product,
        String version) {
}
