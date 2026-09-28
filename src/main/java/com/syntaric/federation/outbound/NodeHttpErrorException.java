// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A member node answered a query with a non-200 status of its own.
 *
 * <p>Distinct from a bare {@link IOException} on purpose: §11.4 fails a query
 * with {@code 424} when a node <em>answered with an error</em> and with
 * {@code 504} when it <em>did not answer</em>, so the fan-out has to be able to
 * tell the two apart at the catch site. The message is unchanged from the plain
 * exception this replaces ({@code "Node returned HTTP n"}), so anything that
 * matched on it still does.
 *
 * <p>The body is kept, truncated and with control characters stripped, because
 * it is the node's own diagnostic and the per-endpoint {@code error} field is
 * where §11.2 says such detail belongs. It is bounded so a node cannot inflate
 * the gateway's response, and cleaned so it cannot smuggle line breaks into a
 * log line or a JSON string.
 */
public class NodeHttpErrorException extends IOException {

    /** Enough to carry an openEHR error body's {@code message}; not enough to echo a page. */
    static final int MAX_BODY_CHARS = 1024;

    private final int status;
    private final String body;

    public NodeHttpErrorException(final int status, final byte[] body) {
        super("Node returned HTTP " + status);
        this.status = status;
        this.body = sanitise(body);
    }

    public int status() {
        return status;
    }

    /** The node's body, cleaned and bounded; empty when it sent none. */
    public String body() {
        return body;
    }

    private static String sanitise(final byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        final String text = new String(raw, StandardCharsets.UTF_8);
        final String bounded = text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) + "…" : text;
        return bounded.replaceAll("[\\p{Cntrl}]+", " ").trim();
    }
}
