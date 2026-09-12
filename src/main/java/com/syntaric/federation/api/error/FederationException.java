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
}
