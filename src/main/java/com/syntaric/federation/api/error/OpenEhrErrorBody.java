// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * openEHR ITS-REST error body shape, used on all {@code /v1/**} routes.
 * {@code /admin/**} uses RFC 9457 ProblemDetail instead.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OpenEhrErrorBody(String error, String message, Map<String, Object> details) {

    public static OpenEhrErrorBody of(final FedErrorCode code, final String message, final Map<String, Object> details) {
        return new OpenEhrErrorBody(code.name(), message, details);
    }
}
