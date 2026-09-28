// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * openEHR ITS-REST error body shape, used on all {@code /v1/**} routes.
 * {@code /admin/**} uses RFC 9457 ProblemDetail instead.
 *
 * <p>{@code meta} is present only on a fan-out that failed under the
 * all-or-nothing default: §11.4 requires a {@code 504}/{@code 424} to keep the
 * {@code meta.federation.endpoints[]} envelope so the failure is diagnosable
 * rather than a bare status line.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OpenEhrErrorBody(String error, String message, Map<String, Object> details, Object meta) {

    public OpenEhrErrorBody(final String error, final String message, final Map<String, Object> details) {
        this(error, message, details, null);
    }

    public static OpenEhrErrorBody of(final FedErrorCode code, final String message, final Map<String, Object> details) {
        return new OpenEhrErrorBody(code.name(), message, details, null);
    }

    public static OpenEhrErrorBody of(final FedErrorCode code, final String message, final Map<String, Object> details,
                                      final Object meta) {
        return new OpenEhrErrorBody(code.name(), message, details, meta);
    }
}
