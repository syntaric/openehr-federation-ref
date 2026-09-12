// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.security;

import com.syntaric.federation.config.FederationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption of the outbound credentials stored on {@code endpoint}
 * (the SMART signing key PEM, the OAuth2 {@code client_secret}).
 *
 * <p><b>Why encrypt at all, given the database is already access-controlled?</b>
 * These are the credentials that let the gateway speak <i>as itself</i> to every member
 * node. A registry dump — a backup, a support export, a replica someone points a
 * BI tool at — would otherwise carry the federation's whole outbound identity in
 * plaintext. The threat is the copy of the data that escapes the database's
 * access control, not the database.
 *
 * <p><b>The stored form is self-describing:</b>
 * {@code <tag>:<base64(iv)>:<base64(ciphertext||gcmTag)>}. The leading algorithm
 * tag exists so that a future key rotation or algorithm change is a migration
 * someone can write, rather than a decrypt that fails with no way to tell
 * "wrong key" from "older format". Without it the only recovery from a changed
 * scheme is to re-enter every credential in the federation by hand.
 */
@Component
public class RegistrySecretCipher {

    /** Current stored-form tag. A new scheme takes a new tag, never a new meaning for this one. */
    static final String TAG_V1 = "v1";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public RegistrySecretCipher(final FederationProperties properties) {
        this.key = parseKey(properties.security().registrySecretKey());
    }

    /**
     * Whether a key is configured at all. Callers use this to fail <i>at boot</i>
     * when the registry holds ciphertext and the deployment has no key, rather
     * than letting every federated query discover it one 401 at a time.
     */
    public boolean configured() {
        return key != null;
    }

    /**
     * @param plaintext the secret, or null
     * @return stored form, or null when {@code plaintext} is null/blank — an
     *         absent credential is a legitimate state (the endpoint uses a
     *         profile that needs none), so it is not an encryption error.
     */
    public String encrypt(final String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        requireKey();
        try {
            final byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            final byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            final Base64.Encoder base64 = Base64.getEncoder();
            return TAG_V1 + ":" + base64.encodeToString(iv) + ":" + base64.encodeToString(ciphertext);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to encrypt registry secret", e);
        }
    }

    /**
     * @param stored stored form, or null
     * @return the plaintext, or null when {@code stored} is null/blank
     * @throws RegistrySecretException when the value cannot be decrypted —
     *         deliberately loud. Returning null for an undecryptable secret
     *         would silently turn a key misconfiguration into "this endpoint has
     *         no credentials", i.e. every federated query to it unauthorized,
     *         with nothing in the logs naming the real cause.
     */
    public String decrypt(final String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        requireKey();
        final String[] parts = stored.split(":", 3);
        if (parts.length != 3 || !TAG_V1.equals(parts[0])) {
            throw new RegistrySecretException(
                    "Stored secret is not in the expected '" + TAG_V1 + ":iv:ciphertext' form");
        }
        try {
            final byte[] iv = Base64.getDecoder().decode(parts[1]);
            final byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            // AEAD: this is authentication failure, which means the wrong key or
            // a tampered value. Both are the operator's problem to hear about.
            throw new RegistrySecretException(
                    "Failed to decrypt registry secret — wrong federation.security.registry-secret-key, "
                            + "or the stored value was modified outside this gateway", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new RegistrySecretException(
                    "federation.security.registry-secret-key is not configured, so per-endpoint "
                            + "outbound credentials cannot be read or written");
        }
    }

    /**
     * Accepts a base64 (128/192/256-bit) key, or any longer passphrase which is
     * folded to 256 bits with SHA-256.
     *
     * <p>The passphrase path is a convenience for dev and small deployments, not
     * a recommendation: it is only as strong as the passphrase. It exists so
     * that getting started does not require generating key material, which in
     * practice is what makes people leave encryption off.
     */
    private static SecretKeySpec parseKey(final String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        final String value = configured.trim();
        try {
            final byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) {
                return new SecretKeySpec(decoded, "AES");
            }
        } catch (final IllegalArgumentException notBase64) {
            // fall through to the passphrase path
        }
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to derive registry secret key", e);
        }
    }

    /** Decryption or configuration failure. Never carries the secret itself. */
    public static class RegistrySecretException extends RuntimeException {

        public RegistrySecretException(final String message) {
            super(message);
        }

        public RegistrySecretException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
