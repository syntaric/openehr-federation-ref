// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

/**
 * A registry write conflicts with what is already there — a duplicate id, or a
 * {@code system_id} already claimed by another node (plan 3 §A1). Rendered as
 * <b>409</b>.
 *
 * <p>Raised ahead of the insert rather than translated from the database's
 * unique-constraint violation: the pre-check can name which node holds the
 * {@code system_id}, which is the one fact that makes the error actionable in
 * the form.
 */
public class RegistryConflictException extends RuntimeException {

    public RegistryConflictException(final String message) {
        super(message);
    }
}
