// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.proxy;

import java.util.Set;

/**
 * Headers that must not be forwarded across a proxy hop (RFC 9110 §7.6.1),
 * plus those the JDK HttpClient manages itself.
 */
public final class HopByHopHeaders {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
            // managed by the client / container:
            "host", "content-length", "expect",
            // byte-identity: the gateway always talks identity encoding to nodes,
            // so origin-side compression can never rewrite bodies or ETags in flight
            "accept-encoding");

    private HopByHopHeaders() {
    }

    public static boolean isHopByHop(final String headerName) {
        return HOP_BY_HOP.contains(headerName.toLowerCase());
    }
}
