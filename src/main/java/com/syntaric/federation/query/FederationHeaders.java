// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query;

/** Wire-visible federation header names (spec §8.4, §7a.3, §11.4/§11.5). */
public final class FederationHeaders {

    public static final String ENDPOINT = "openEHR-federation-endpoint";
    public static final String ORGANISATION = "openEHR-federation-organisation";
    public static final String SYSTEM_ID = "openEHR-federation-system-id";
    public static final String COMPLETENESS = "openEHR-federation-completeness";
    /** Deployment-defined opt-in dedup selector; declared in OPTIONS. */
    public static final String DEDUP = "openEHR-federation-dedup";
    public static final String PREFER = "Prefer";

    public static final String COMPLETENESS_ALL = "all";
    public static final String DEDUP_VERSION_IDENTITY = "version-identity";
    public static final String DEDUP_NONE = "none";

    private FederationHeaders() {
    }
}
