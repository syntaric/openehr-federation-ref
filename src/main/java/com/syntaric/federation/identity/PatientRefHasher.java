// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity;

import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.identity.spi.PatientToken;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * HMAC-SHA256 over {@code system|value}. Only this hash — never the raw
 * patient identifier — is stored in {@code resolution_binding} or written to
 * audit details (plan invariant #6).
 */
@Component
public class PatientRefHasher {

    private final SecretKeySpec key;

    public PatientRefHasher(final FederationProperties properties) {
        this.key = new SecretKeySpec(
                properties.identity().refHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String hash(final PatientToken token) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            final byte[] digest = mac.doFinal(
                    (token.system() + "|" + token.value()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (final Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
