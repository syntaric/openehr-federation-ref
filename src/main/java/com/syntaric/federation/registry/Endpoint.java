// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One source's connection details.
 *
 * <p><b>Component order is load-bearing.</b> Spring Data JDBC binds by name, but
 * {@code JdbcAggregateTemplate.insert} writes the columns in declaration order
 * and every construction site here is positional — so a component inserted in
 * the wrong place misbinds silently rather than failing to compile. New columns
 * go at the end, and {@code RegistryImportIT} round-trips every one of them
 * through a document with a distinctive value per component, so a shift by one
 * lands the wrong value in the wrong column and fails loudly.
 *
 * <p>The {@code *Enc} components hold ciphertext ({@code RegistrySecretCipher}),
 * never a plaintext secret, and are {@code @JsonIgnore}d so they cannot reach
 * the registry export — see {@link #smartSigningKeyEnc()}.
 */
@Table("endpoint")
public record Endpoint(
        @Id String endpointId,
        String nodeId,
        String baseUrl,
        String pixManagerUrl,
        String connectionType,
        String authProfile,
        String status,
        Long latencyP50Ms,
        String smartClientId,
        String smartTokenEndpoint,
        @JsonIgnore String smartSigningKeyEnc,
        String smartScope,
        String oauthClientId,
        String oauthTokenEndpoint,
        @JsonIgnore String oauthClientSecretEnc,
        String oauthScope,
        String oauthAuthMethod,
        String oauthAudience) {

    /** SMART Backend Services default when {@link #smartScope()} is unset. */
    public static final String DEFAULT_SMART_SCOPE = "system/*.read system/*.write";

    /**
     * The connection half of an endpoint, with no outbound credentials.
     *
     * <p>Exists so the many construction sites that predate per-endpoint
     * credentials — and every caller that genuinely has none — do not each carry
     * ten trailing nulls. Those nulls are not merely noise: a row of them is
     * where a future component gets inserted in the wrong position, which
     * misbinds columns silently.
     */
    public static Endpoint withoutCredentials(final String endpointId, final String nodeId, final String baseUrl,
                                              final String pixManagerUrl, final String connectionType,
                                              final String authProfile, final String status, final Long latencyP50Ms) {
        return new Endpoint(endpointId, nodeId, baseUrl, pixManagerUrl, connectionType, authProfile,
                status, latencyP50Ms,
                null, null, null, null, null, null, null, null, null, null);
    }

    public static final String AUTH_METHOD_BASIC = "client_secret_basic";
    public static final String AUTH_METHOD_POST = "client_secret_post";

    /**
     * {@code @JsonIgnore} on the two {@code *Enc} components is the export half
     * of the secret contract, and it is deliberately on the domain record rather
     * than on a DTO: a registry document serialises {@link Endpoint} directly,
     * and that document is exactly what the bootstrap loader accepts back.
     *
     * <p>Ciphertext is omitted rather than masked. A masked placeholder would be
     * re-imported as a literal value — the round-trip would "succeed" and leave
     * every credential in the target registry set to {@code "********"}, failing
     * only later, on a clinical query. {@code RegistryService.importRegistry}
     * completes the contract by treating an absent secret as "keep what is
     * stored".
     */
    @JsonIgnore
    public boolean smartSigningKeySet() {
        return isSet(smartSigningKeyEnc);
    }

    @JsonIgnore
    public boolean oauthClientSecretSet() {
        return isSet(oauthClientSecretEnc);
    }

    /** True when either encrypted column holds a value — drives boot-time key validation. */
    @JsonIgnore
    public boolean hasEncryptedSecret() {
        return smartSigningKeySet() || oauthClientSecretSet();
    }

    @JsonIgnore
    public String smartScopeOrDefault() {
        return isSet(smartScope) ? smartScope : DEFAULT_SMART_SCOPE;
    }

    /** Unset means {@code client_secret_basic}, the RFC 6749 §2.3.1 default. */
    @JsonIgnore
    public String oauthAuthMethodOrDefault() {
        return isSet(oauthAuthMethod) ? oauthAuthMethod : AUTH_METHOD_BASIC;
    }

    private static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Derived from {@link #status}, and deliberately not serialized.
     *
     * <p>Without {@code @JsonIgnore} this appears in a serialised registry
     * document as an extra {@code "active"} field that is not a record component
     * — so feeding that document back to the bootstrap loader fails to bind and
     * silently imports nothing. Export and import are the same shape; this keeps
     * that true.
     */
    @JsonIgnore
    public boolean isActive() {
        return "active".equals(status);
    }

}
