// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.query.OutboundCredentials;
import com.syntaric.federation.registry.Endpoint;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Plain OAuth2 {@code client_credentials} grant (RFC 6749 §4.4), for a node
 * whose authorization server expects an ordinary {@code client_id} +
 * {@code client_secret} client rather than a SMART client assertion.
 *
 * <p>Without this profile such a node could not be federated at all:
 * {@code smart} sends an RS384 assertion the AS will not accept, and
 * {@code static} means hand-managing a token that expires.
 *
 * <p><b>⚠ The acting user's identity is not propagated on this profile.</b>
 * SMART carries the inbound {@code sub} in the assertion's {@code act} claim
 * (N24); a client-credentials grant has no assertion, so there is nowhere to put
 * it. The node sees only the gateway, and <i>its</i> audit trail records only
 * the gateway. The gateway's own audit trail still records the acting user, so
 * the information is
 * not lost to the federation — but it stops at the gateway boundary. This is a
 * compliance-relevant difference between two options that look interchangeable
 * in a dropdown, which is why the console states it at the point of choice.
 */
@Component
public class OAuth2ClientCredentialsAuthProvider extends TokenAcquiringAuthProvider {

    public OAuth2ClientCredentialsAuthProvider(final TokenEndpointDiscovery discovery) {
        super(discovery);
    }

    /** RFC 6749 §2.3.1 shared secret; see {@link SmartBackendServicesAuthProvider#PROFILE}. */
    public static final String PROFILE = "oauth2-secret";

    @Override
    public String profile() {
        return PROFILE;
    }

    /** RFC 8414. Most CDRs do not publish it, which is what the negative cache is for. */
    @Override
    protected String wellKnownDocument() {
        return TokenEndpointDiscovery.OAUTH_DOCUMENT;
    }


    @Override
    protected TokenRequest prepareRequest(final EndpointDescriptor endpoint, final String inboundAuthorization) {
        final OutboundCredentials credentials = credentials(endpoint);
        require(isSet(credentials.oauthClientId()), endpoint, "an OAuth2 client id");
        require(credentials.oauthClientSecretSet(), endpoint, "an OAuth2 client secret");

        final String tokenEndpoint = requireTokenEndpoint(endpoint, credentials.oauthTokenEndpoint());

        final StringBuilder form = new StringBuilder("grant_type=client_credentials");
        // Omitted rather than sent empty when unset. There is no safe default
        // scope for a plain OAuth2 AS — scopes are deployment-specific, and a
        // guess yields a token the AS happily issues and the node then refuses.
        if (isSet(credentials.oauthScope())) {
            form.append("&scope=").append(encode(credentials.oauthScope()));
        }
        // Some authorization servers will not mint a node-usable token without it.
        if (isSet(credentials.oauthAudience())) {
            form.append("&audience=").append(encode(credentials.oauthAudience()));
        }

        if (Endpoint.AUTH_METHOD_POST.equals(credentials.oauthAuthMethod())) {
            form.append("&client_id=").append(encode(credentials.oauthClientId()))
                    .append("&client_secret=").append(encode(credentials.oauthClientSecret()));
            return new TokenRequest(tokenEndpoint, form.toString());
        }
        return new TokenRequest(tokenEndpoint, form.toString(),
                Map.of("Authorization", basicAuth(credentials)));
    }

    /**
     * RFC 6749 §2.3.1: both halves are <b>form-urlencoded before</b> being joined
     * with a colon and base64'd.
     *
     * <p>Easy to skip, and it only breaks for some secrets — one containing
     * {@code +}, {@code /} or {@code =} authenticates fine against a lenient AS
     * and fails against a conformant one, which makes it look like the node's
     * problem. Encoding always is both correct and unambiguous.
     */
    private static String basicAuth(final OutboundCredentials credentials) {
        final String value = encode(credentials.oauthClientId()) + ":" + encode(credentials.oauthClientSecret());
        return "Basic " + Base64.getEncoder()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
