// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api.error;

import java.util.Map;

/** Single internal exception currency for federation failures. */
public class FederationException extends RuntimeException {

    private final FedErrorCode code;
    private final transient Map<String, Object> details;

    public FederationException(final FedErrorCode code, final String message) {
        this(code, message, Map.of());
    }

    public FederationException(final FedErrorCode code, final String message, final Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public FedErrorCode code() {
        return code;
    }

    /** Structured, non-sensitive extras (e.g. the controlling system_id of a 409). */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * The result-set {@code meta} a failing fan-out must still carry (§11.4), or
     * null for every other failure.
     *
     * <p>Typed {@code Object} rather than the envelope's own record so this
     * package never imports {@code query}: the error currency sits below the
     * engine, and the subclass that knows the envelope lives up there.
     */
    public Object meta() {
        return null;
    }
}
