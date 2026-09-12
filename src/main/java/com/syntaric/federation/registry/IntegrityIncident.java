// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("integrity_incident")
public record IntegrityIncident(
        @Id Long id,
        String type,
        String detail,
        Instant createdAt) {

    public static final String TYPE_EHR_ID_COLLISION = "EHR_ID_COLLISION";
}
