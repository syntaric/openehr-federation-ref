// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.definition;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;

import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stored-query version in ITS-REST's own semver path segment, {@code x.y.z}
 * (§12.7: the registry MUST reuse it rather than invent a parallel scheme).
 *
 * <p>Strict on purpose: no pre-release or build suffix, no {@code v} prefix, no
 * two-component form. ITS-REST resolves an omitted version to the latest, and
 * "latest" is only well-defined over versions that compare numerically.
 */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

    private static final Pattern STRICT = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");

    private static final Comparator<SemVer> ORDER = Comparator
            .comparingInt(SemVer::major)
            .thenComparingInt(SemVer::minor)
            .thenComparingInt(SemVer::patch);

    public static SemVer parse(final String version) {
        final Matcher m = version == null ? null : STRICT.matcher(version);
        if (m == null || !m.matches()) {
            throw new FederationException(FedErrorCode.FED_STORED_QUERY_INVALID,
                    "Stored query version must be strict semver (major.minor.patch)");
        }
        try {
            return new SemVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (final NumberFormatException e) {
            throw new FederationException(FedErrorCode.FED_STORED_QUERY_INVALID,
                    "Stored query version component is out of range");
        }
    }

    @Override
    public int compareTo(final SemVer other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
