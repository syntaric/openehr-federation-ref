// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An external identifier by which a localization service names a member node
 * (N4 / CP-5).
 *
 * <p>Deliberately a side table rather than columns on {@code node}: a node may
 * legitimately carry several identifiers of the same system — one gateway
 * fronting two XCPD communities is an ordinary arrangement — and the set of
 * systems grows with every localizer the federation learns to speak to.
 */
@Table("node_identifier")
public record NodeIdentifier(
        @Id Long id,
        String nodeId,
        String system,
        String value) {

    /** A care-provider registration number, as several national locators use. */
    public static final String SYSTEM_URA = "ura";

    /** IHE XCPD {@code HomeCommunityId} OID. */
    public static final String SYSTEM_HOME_COMMUNITY_ID = "home-community-id";

    /** IHE XCPD {@code SourceId} OID. */
    public static final String SYSTEM_SOURCE_ID = "source-id";
}
