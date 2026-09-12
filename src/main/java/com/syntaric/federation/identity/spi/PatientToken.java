// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.spi;

/**
 * A directly identifying patient identifier plus its issuing namespace.
 * The value is sensitive: {@code toString()} is redacting, and the raw value
 * must never be persisted, logged or dispatched (N33).
 */
public record PatientToken(String value, String system) {

    @Override
    public String toString() {
        return "PatientToken[system=" + system + ", value=REDACTED]";
    }
}
