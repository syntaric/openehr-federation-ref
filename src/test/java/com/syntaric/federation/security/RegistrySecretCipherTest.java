// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.security;

import com.syntaric.federation.config.FederationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The encryption underneath per-endpoint outbound credentials.
 *
 * <p>A mistake here is a security bug rather than a broken feature, so the
 * properties pinned are the ones that would otherwise fail silently: that a
 * wrong key is <i>loud</i>, that ciphertext does not leak its plaintext, and
 * that an unconfigured key cannot be mistaken for "no secret".
 */
class RegistrySecretCipherTest {

    private static final String PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ
            -----END PRIVATE KEY-----""";

    private static RegistrySecretCipher cipher(String key) {
        return new RegistrySecretCipher(properties(key));
    }

    private static FederationProperties properties(String key) {
        return new FederationProperties(
                new FederationProperties.Federation("f", "f", null, "test-gateway", "0", null),
                new FederationProperties.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(10),
                        Duration.ofSeconds(2)),
                new FederationProperties.Identity(FederationProperties.Identity.Mode.STATIC, Map.of(),
                        "secret", Duration.ofHours(1)),
                com.syntaric.federation.config.LocalizationProperties.disabled(),
                new FederationProperties.Security("passthrough", Map.of(), key),
                null);
    }

    private static String base64Key(byte seed) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, seed);
        return Base64.getEncoder().encodeToString(key);
    }

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        void encryptsAndDecryptsAMultiLinePem() {
            RegistrySecretCipher cipher = cipher(base64Key((byte) 1));
            assertThat(cipher.decrypt(cipher.encrypt(PEM))).isEqualTo(PEM);
        }

        @Test
        @DisplayName("a passphrase key works, for deployments that do not generate key material")
        void acceptsAPassphrase() {
            RegistrySecretCipher cipher = cipher("a long deployment passphrase");
            assertThat(cipher.decrypt(cipher.encrypt("s3cr3t"))).isEqualTo("s3cr3t");
        }

        @Test
        @DisplayName("the same plaintext encrypts differently each time (random IV)")
        void isNotDeterministic() {
            RegistrySecretCipher cipher = cipher(base64Key((byte) 1));
            assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
        }

        @Test
        @DisplayName("the stored form carries a version tag, so a scheme change can be migrated")
        void storedFormIsSelfDescribing() {
            assertThat(cipher(base64Key((byte) 1)).encrypt("x")).startsWith("v1:");
        }

        @Test
        void ciphertextDoesNotContainThePlaintext() {
            assertThat(cipher(base64Key((byte) 1)).encrypt("BEGIN PRIVATE KEY material"))
                    .doesNotContain("PRIVATE")
                    .doesNotContain("material");
        }

        @Test
        @DisplayName("an absent secret is null, not an error — a profile may need none")
        void nullAndBlankRoundTripAsNull() {
            RegistrySecretCipher cipher = cipher(base64Key((byte) 1));
            assertThat(cipher.encrypt(null)).isNull();
            assertThat(cipher.encrypt("  ")).isNull();
            assertThat(cipher.decrypt(null)).isNull();
        }
    }

    @Nested
    @DisplayName("failing loudly")
    class Failures {

        /**
         * The behaviour that keeps a key mix-up from becoming "every federated
         * query is unauthorized, with nothing in the log saying why".
         */
        @Test
        @DisplayName("the wrong key fails rather than returning garbage or null")
        void wrongKeyThrows() {
            String stored = cipher(base64Key((byte) 1)).encrypt(PEM);

            assertThatThrownBy(() -> cipher(base64Key((byte) 2)).decrypt(stored))
                    .isInstanceOf(RegistrySecretCipher.RegistrySecretException.class)
                    .hasMessageContaining("registry-secret-key");
        }

        @Test
        @DisplayName("a tampered value fails: GCM authenticates, it does not merely decrypt")
        void tamperedCiphertextThrows() {
            String stored = cipher(base64Key((byte) 1)).encrypt(PEM);
            String tampered = stored.substring(0, stored.length() - 4) + "AAAA";

            assertThatThrownBy(() -> cipher(base64Key((byte) 1)).decrypt(tampered))
                    .isInstanceOf(RegistrySecretCipher.RegistrySecretException.class);
        }

        @Test
        @DisplayName("a plaintext value written into the column by hand is rejected, not used")
        void unknownStoredFormThrows() {
            assertThatThrownBy(() -> cipher(base64Key((byte) 1)).decrypt(PEM))
                    .isInstanceOf(RegistrySecretCipher.RegistrySecretException.class)
                    .hasMessageContaining("expected");
        }

        @Test
        @DisplayName("with no key configured, reading a secret is an error — never silently absent")
        void missingKeyThrowsRatherThanReturningNull() {
            RegistrySecretCipher cipher = cipher(null);
            assertThat(cipher.configured()).isFalse();

            assertThatThrownBy(() -> cipher.decrypt("v1:aaaa:bbbb"))
                    .isInstanceOf(RegistrySecretCipher.RegistrySecretException.class)
                    .hasMessageContaining("not configured");
            assertThatThrownBy(() -> cipher.encrypt("x"))
                    .isInstanceOf(RegistrySecretCipher.RegistrySecretException.class);
        }

        @Test
        @DisplayName("no exception message carries the secret")
        void messagesNeverEchoTheSecret() {
            String stored = cipher(base64Key((byte) 1)).encrypt("hunter2-the-password");

            assertThatThrownBy(() -> cipher(base64Key((byte) 2)).decrypt(stored))
                    .hasMessageNotContaining("hunter2");
        }
    }
}
