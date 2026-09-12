// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.security;

import com.syntaric.federation.query.OutboundCredentials;
import com.syntaric.federation.registry.Endpoint;
import org.springframework.stereotype.Component;

/**
 * Turns an {@link Endpoint} row into the decrypted {@link OutboundCredentials}
 * the auth providers use.
 *
 * <p>One place does the decrypting, so there is one place where a decrypt
 * failure is handled rather than one per call site.
 *
 * <p>Note what is <b>not</b> here: a "is this endpoint configured?" predicate.
 * {@code AdminConfigController} once computed one from client id + token
 * endpoint while the runtime also demanded the signing key, so setting two of
 * the three showed "configured" in the console while every federated call threw.
 * The fix is not a shared predicate but removing the second opinion: readiness
 * is enforced at save by {@code AdminSourceController.validateCredentials} and
 * surfaced per secret by the form's own stored-secret flags, so nothing
 * recomputes it and nothing can drift.
 */
@Component
public class OutboundCredentialsResolver {

    private final RegistrySecretCipher cipher;

    public OutboundCredentialsResolver(final RegistrySecretCipher cipher) {
        this.cipher = cipher;
    }

    /**
     * @throws RegistrySecretCipher.RegistrySecretException when a stored secret
     *         cannot be decrypted. Propagated rather than swallowed: the caller
     *         is about to authenticate to a member node, and "no credentials"
     *         is a different fact from "the credentials are unreadable".
     */
    public OutboundCredentials resolve(final Endpoint endpoint) {
        if (endpoint == null) {
            return OutboundCredentials.NONE;
        }
        return new OutboundCredentials(
                endpoint.smartClientId(),
                endpoint.smartTokenEndpoint(),
                cipher.decrypt(endpoint.smartSigningKeyEnc()),
                endpoint.smartScopeOrDefault(),
                endpoint.oauthClientId(),
                endpoint.oauthTokenEndpoint(),
                cipher.decrypt(endpoint.oauthClientSecretEnc()),
                endpoint.oauthScope(),
                endpoint.oauthAuthMethodOrDefault(),
                endpoint.oauthAudience());
    }
}
