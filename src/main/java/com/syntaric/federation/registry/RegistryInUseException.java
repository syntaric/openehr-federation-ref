// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import java.util.Map;

/**
 * A registry row cannot be hard-deleted because other rows still reference it
 * (plan 3 §A1).
 *
 * <p>Carries the per-table reference counts rather than a bare message: the
 * operator's next question is always "referenced by what, and how much?", and
 * the answer decides whether they wait, clean up, or take the soft path
 * ({@code status: "excluded"}) the problem detail points at.
 *
 * <p>Deliberately not a {@code FederationException}: this is an operator-console
 * conflict with its own problem shape, not a federation error, and it must never
 * be rendered in the openEHR error body.
 */
public class RegistryInUseException extends RuntimeException {

    private final String kind;
    private final String id;
    private final transient Map<String, Long> references;

    public RegistryInUseException(final String kind, final String id, final Map<String, Long> references) {
        super(kind + " '" + id + "' is referenced by existing rows and cannot be deleted");
        this.kind = kind;
        this.id = id;
        this.references = references;
    }

    public String kind() {
        return kind;
    }

    public String id() {
        return id;
    }

    /** Non-zero reference counts, keyed by the referencing table's UI-facing name. */
    public Map<String, Long> references() {
        return references;
    }
}
