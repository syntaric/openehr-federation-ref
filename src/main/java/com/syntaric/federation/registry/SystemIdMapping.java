// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("system_id_mapping")
public record SystemIdMapping(
        @Id String creatingSystemId,
        String nodeId,
        String baseUrl,
        String source) {

    public static final String SOURCE_REGISTRY = "registry";
    public static final String SOURCE_OBSERVED = "observed";
}
