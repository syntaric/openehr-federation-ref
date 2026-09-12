// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.Map;

/**
 * Root configuration for the federation gateway, bound from {@code federation.*}.
 */
@ConfigurationProperties("federation")
public record FederationProperties(
        @DefaultValue Federation federation,
        @DefaultValue Timeouts timeouts,
        @DefaultValue Identity identity,
        @DefaultValue Localization localization,
        @DefaultValue Security security,
        @DefaultValue Registry registry) {

    /**
     * How the federation registry is populated at startup.
     *
     * <p>This build exposes no registry write API, so a bootstrap document is the
     * only way in. That is a deliberate scope choice rather than an omission: any
     * {@code /admin/**} path needs an authorization story, and the specification's
     * only defined introspection surface is the N30 self-description.
     */
    public record Registry(
            /**
             * A registry document to apply at startup, as a Spring resource —
             * {@code file:/etc/federation/registry.json} or
             * {@code classpath:registry.json}.
             *
             * <p>Unset (the default) is a silent no-op, which is what lets a
             * deployment manage the registry tables by other means. When it IS
             * set, a missing or malformed file fails the boot rather than
             * starting a gateway with an empty federation that answers every
             * query with zero rows and looks healthy doing it.
             *
             * <p>Applying it is idempotent — upsert per id, never deletes, rows
             * absent from the document left alone — so restarting is always safe
             * and fixing a bad file is edit-and-restart.
             */
            Resource bootstrapFile) {
    }

    /** Static identity of this federation deployment, surfaced in {@code OPTIONS {base}/}. */
    public record Federation(
            @DefaultValue("reference-federation") String id,
            @DefaultValue("openEHR Federation Reference Gateway") String name,
            String organisation,
            @DefaultValue("openehr-federation-ref") String product,
            @DefaultValue("0.1.0") String version,
            /**
             * Where this gateway's JWKS is served, declared as
             * {@code federation.auth.jwks_uri} in {@code OPTIONS {base}/} so a node
             * can obtain the keys it verifies our RFC 7523 client assertions against
             * (§13.1, §7a.2). The spec requires the location be <i>discoverable</i>,
             * not that we host it — a deployment serves the key set from wherever its
             * PKI already lives and points this there.
             *
             * <p>Unset means we declare no location, which is honest but leaves a node
             * unable to verify without an out-of-band arrangement.
             */
            String jwksUri) {
    }

    /**
     * N38: both the per-node timeout and the overall budget are mandatory and are
     * declared in the OPTIONS self-description.
     */
    public record Timeouts(
            @DefaultValue("10s") Duration perNode,
            @DefaultValue("15s") Duration overallBudget,
            @DefaultValue("2s") Duration connect) {
    }

    /** Patient identity resolution strategy: static config map (dev/test) or PIXm. */
    public record Identity(
            @DefaultValue("static") Mode mode,
            /** patient identifier -> (endpointId -> local ehr_id); used by mode=static. */
            @DefaultValue Map<String, Map<String, String>> staticMappings,
            /** HMAC-SHA256 key for hashing patient refs at rest; never store raw values. */
            @DefaultValue("change-me-in-deployment") String refHashSecret,
            @DefaultValue("24h") Duration bindingTtl) {

        public enum Mode { STATIC, PIXM }
    }

    /**
     * N4 / CP-5: which localization (record-locator) service narrows an
     * undirected query's candidate node set.
     *
     * <p>{@code mode=none} is the spec's explicit <i>fallback</i> (§4.3 variant
     * B) — ask every known node — and is the default. The other modes select the
     * regional Annex B adapters, which this build ships as documented stubs; they
     * are <b>alternatives</b>, never layered, because a federation has exactly one
     * localization authority and consulting two would produce a union neither of
     * them sanctioned.
     */
    public record Localization(
            @DefaultValue("none") Mode mode,
            /**
             * What a backend failure means. {@code closed} — the default — treats an
             * unreachable localizer as "no node may be asked", because the whole
             * point of localization is that the federation must not learn who asked
             * about a patient. {@code ask-all} trades that privacy property for
             * availability and is a deliberate, audited deployment choice.
             */
            @DefaultValue("closed") OnFailure onFailure,
            /**
             * Localization's own budget, deliberately <b>not</b> drawn from
             * {@link Timeouts#overallBudget}. The localization call happens inside
             * the clinical request, so without a separate bound a slow national
             * service silently eats the fan-out allowance and member nodes start
             * reporting {@code time-out} for a reason that has nothing to do with
             * them. What is left of the overall budget is what the fan-out gets.
             */
            @DefaultValue("3s") Duration timeout,
            /**
             * How long a localization answer may be reused. Short by default: a
             * consent can be revoked at any moment, so this is a correctness
             * bound on how stale an inclusion decision may be, not a cache-tuning
             * knob.
             */
            @DefaultValue("15m") Duration cacheTtl) {

        public enum Mode {
            NONE, NVI, MITZ;

            /**
             * The {@code @ConditionalOnProperty(havingValue = …)} string this
             * constant selects. Spring matches the property text, not the enum, so
             * the two can drift silently — {@code LocalizationModeWiringTest} pins
             * them together.
             */
            public String propertyValue() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        public enum OnFailure { CLOSED, ASK_ALL }
    }

    /**
     * Credentials the gateway uses when calling <b>out</b> to member nodes.
     *
     * <p>There is deliberately nothing here about inbound authentication. This
     * build does not authenticate its own callers: the specification defines the
     * federation tier's behaviour, not who is allowed to reach it, and an access
     * control model is a deployment concern that varies by jurisdiction. A
     * deployment puts this gateway behind whatever its own policy requires.
     *
     * <p>The consequence is blunt and worth stating: <b>anything that can reach
     * this port can query the federation.</b> Do not expose it directly.
     */
    public record Security(
            /** Outbound auth profile applied when an endpoint does not declare its own. */
            @DefaultValue("passthrough") String defaultOutboundProfile,
            @DefaultValue Map<String, String> staticTokens,
            /**
             * Key encrypting the outbound credentials stored on {@code endpoint}
             * — the SMART signing key and the OAuth2 client secret.
             *
             * <p>A base64 AES key (16/24/32 bytes) or a passphrase, which is
             * folded to 256 bits. <b>Losing it means every stored credential is
             * unrecoverable</b> and must be re-entered: there is no escrow and, by
             * design, no plaintext copy anywhere.
             * {@code RegistrySecretKeyValidator} fails the boot rather than
             * letting that surface one unauthorized query at a time.
             */
            String registrySecretKey) {

        // federation.security.smart.* used to live here: one client id, one token
        // endpoint and one signing key for the whole federation. It is gone
        // rather than kept as a fallback, because a single outbound identity is
        // wrong the moment nodes are separately operated — which is the premise
        // of federating at all. A token minted by node A's authorization server
        // is rejected by node B on iss/aud, or worse, accepted. Outbound
        // credentials are now per-endpoint registry data.
    }

}
