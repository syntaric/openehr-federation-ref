// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.security;

import com.syntaric.federation.registry.Endpoint;
import com.syntaric.federation.registry.EndpointRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Refuses to run a gateway that cannot read its own stored credentials.
 *
 * <p>The failure this prevents is quiet and expensive: with credentials in the
 * registry and no {@code federation.security.registry-secret-key}, every
 * token-acquiring endpoint would resolve to "no credentials" and every federated
 * query to it would come back unauthorized — a clinical-path failure whose cause
 * is a missing environment variable several layers away. Failing the boot turns
 * that into a startup error that names the affected endpoints.
 *
 * <p>Runs on {@code ApplicationReadyEvent} rather than as an
 * {@code InitializingBean} so that Flyway has certainly applied V10 first; the
 * check reads columns that migration adds.
 */
@Component
public class RegistrySecretKeyValidator {

    private final EndpointRepository endpoints;
    private final RegistrySecretCipher cipher;

    public RegistrySecretKeyValidator(final EndpointRepository endpoints, final RegistrySecretCipher cipher) {
        this.endpoints = endpoints;
        this.cipher = cipher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        final List<Endpoint> withSecrets = endpoints.findAll().stream()
                .filter(Endpoint::hasEncryptedSecret)
                .toList();
        if (withSecrets.isEmpty()) {
            return;
        }
        if (!cipher.configured()) {
            throw new IllegalStateException(missingKeyMessage(withSecrets));
        }
        final List<String> undecryptable = withSecrets.stream()
                .filter(this::undecryptable)
                .map(Endpoint::endpointId)
                .toList();
        if (!undecryptable.isEmpty()) {
            throw new IllegalStateException(
                    "federation.security.registry-secret-key does not decrypt the stored outbound "
                            + "credentials of endpoint(s) " + undecryptable + ". The key has changed, "
                            + "or these rows were written by a different deployment. Restore the "
                            + "previous key, or clear and re-enter the credentials in the console.");
        }
    }

    private boolean undecryptable(final Endpoint endpoint) {
        try {
            cipher.decrypt(endpoint.smartSigningKeyEnc());
            cipher.decrypt(endpoint.oauthClientSecretEnc());
            return false;
        } catch (final RegistrySecretCipher.RegistrySecretException e) {
            return true;
        }
    }

    private static String missingKeyMessage(final List<Endpoint> withSecrets) {
        final List<String> ids = withSecrets.stream().map(Endpoint::endpointId).toList();
        return "Endpoint(s) " + ids + " hold encrypted outbound credentials but "
                + "federation.security.registry-secret-key is not configured. Without it those "
                + "credentials cannot be read and every federated query to those sources would "
                + "be sent unauthenticated. Set the key that encrypted them.";
    }
}
