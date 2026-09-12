// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.OutboundCredentials;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMART Backend Services client-assertion exchange (spec §"security" proposed
 * binding): a signed RS384 JWT assertion is exchanged at the authorization
 * server's token endpoint for a bearer token, cached per endpoint until close to
 * expiry.
 *
 * <p><b>Credentials are per endpoint</b>, read from the registry row rather than
 * from one {@code federation.security.smart.*} block shared by the whole federation.
 * That block is gone: with separately operated nodes it could only ever register
 * one authorization server, and a token minted by one node's AS is rejected by
 * another on {@code iss}/{@code aud}.
 *
 * <p><b>Identity propagation.</b> The inbound client identity travels in the
 * assertion's {@code act} claim so the node can audit the acting user (N24).
 * This is an advisory claim the gateway signs <i>about</i> the user — the node
 * authorizes the gateway, not the user; real delegation would be RFC 8693 token
 * exchange. It is still strictly more than the {@code oauth2} profile can
 * offer, which has no assertion and therefore nowhere to carry it at all.
 */
@Component
public class SmartBackendServicesAuthProvider extends TokenAcquiringAuthProvider {

    private static final String ASSERTION_TYPE =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    /**
     * Parsed signing keys, by PEM.
     *
     * <p>{@code parsePrivateKey} used to run on every exchange, inside the token
     * cache's {@code compute} — i.e. an RSA key parse while holding a lock on
     * the map every federated request consults. It is deterministic and the
     * input set is small (one PEM per endpoint), so it is cached.
     */
    private final Map<String, RSAPrivateKey> parsedKeys = new ConcurrentHashMap<>();

    public SmartBackendServicesAuthProvider(final TokenEndpointDiscovery discovery) {
        super(discovery);
    }

    /**
     * {@code oauth2-jwt}, not {@code smart}.
     *
     * <p>Both token-acquiring profiles are OAuth2 {@code client_credentials}
     * grants; what differs is how the client authenticates itself, so the names
     * say that: {@code -jwt} is RFC 7523 {@code private_key_jwt},
     * {@code oauth2-secret} is RFC 6749 §2.3.1 shared secret. Naming one
     * {@code smart} and the other {@code oauth2} implied they were different
     * kinds of thing.
     *
     * <p>The profile still carries SMART's other conventions — the
     * {@code system/*.read} scope syntax and the {@code act} claim — which is why
     * it stays a profile of its own rather than an authentication-method option
     * on {@code oauth2-secret}.
     */
    public static final String PROFILE = "oauth2-jwt";

    @Override
    public String profile() {
        return PROFILE;
    }

    /** SMART's own discovery document, which SMART-capable nodes do publish. */
    @Override
    protected String wellKnownDocument() {
        return TokenEndpointDiscovery.SMART_DOCUMENT;
    }


    @Override
    protected TokenRequest prepareRequest(final EndpointDescriptor endpoint, final String inboundAuthorization) {
        final OutboundCredentials credentials = credentials(endpoint);
        require(isSet(credentials.smartClientId()), endpoint, "a SMART client id");
        require(credentials.smartSigningKeySet(), endpoint, "a SMART signing key");

        final String tokenEndpoint = requireTokenEndpoint(endpoint, credentials.smartTokenEndpoint());
        final String assertion = buildAssertion(credentials, tokenEndpoint, inboundAuthorization);
        final String form = "grant_type=client_credentials"
                + "&scope=" + encode(credentials.smartScope())
                + "&client_assertion_type=" + encode(ASSERTION_TYPE)
                + "&client_assertion=" + encode(assertion);
        return new TokenRequest(tokenEndpoint, form);
    }

    /**
     * @param tokenEndpoint the assertion's {@code aud} — passed in rather than
     *                      re-read so the audience is always the endpoint we are
     *                      actually posting to, or the AS rejects the assertion.
     */
    private String buildAssertion(final OutboundCredentials credentials, final String tokenEndpoint,
                                  final String inboundAuthorization) {
        try {
            final RSAPrivateKey key = parsedKeys.computeIfAbsent(credentials.smartSigningKeyPem(),
                    pem -> {
                        try {
                            return parsePrivateKey(pem);
                        } catch (final Exception e) {
                            // The message never carries the PEM.
                            throw new IllegalStateException(
                                    "SMART signing key is not a readable RSA PKCS#8 private key", e);
                        }
                    });
            final JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(credentials.smartClientId())
                    .subject(credentials.smartClientId())
                    .audience(tokenEndpoint)
                    .jwtID(UUID.randomUUID().toString())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            final String actor = inboundSubject(inboundAuthorization);
            if (actor != null) {
                claims.claim("act", Map.of("sub", actor));
            }
            final SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS384).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (final IllegalStateException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to sign the SMART client assertion", e);
        }
    }

    /** Extracts the inbound JWT's sub claim without verifying (already verified inbound). */
    private String inboundSubject(final String inboundAuthorization) {
        if (inboundAuthorization == null || !inboundAuthorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            return SignedJWT.parse(inboundAuthorization.substring("Bearer ".length()))
                    .getJWTClaimsSet().getSubject();
        } catch (final Exception e) {
            return null;
        }
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** RSA PKCS#8 only ({@code BEGIN PRIVATE KEY}); ES384 is out of scope. */
    public static RSAPrivateKey parsePrivateKey(final String pem) throws Exception {
        final String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        final byte[] der = Base64.getDecoder().decode(base64);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
