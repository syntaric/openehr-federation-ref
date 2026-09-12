// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.outbound.auth;

import com.syntaric.federation.config.FederationProperties;
import com.syntaric.federation.query.EndpointDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Selects the {@link OutboundAuthProvider} for an endpoint's auth profile. */
@Component
public class OutboundAuthProviders {

    private final Map<String, OutboundAuthProvider> byProfile;
    private final String defaultProfile;

    public OutboundAuthProviders(final List<OutboundAuthProvider> providers, final FederationProperties properties) {
        this.byProfile = providers.stream()
                .collect(Collectors.toMap(OutboundAuthProvider::profile, Function.identity()));
        this.defaultProfile = properties.security().defaultOutboundProfile();
    }

    public OutboundAuthProvider forEndpoint(final EndpointDescriptor endpoint) {
        final String profile = endpoint.authProfile() != null ? endpoint.authProfile() : defaultProfile;
        final OutboundAuthProvider provider = byProfile.get(kind(profile));
        if (provider == null) {
            throw new IllegalStateException("No outbound auth provider for profile '" + profile + "'");
        }
        return provider;
    }

    /**
     * The provider-selecting half of a profile name.
     *
     * <p>{@code static:node-a} names a <i>kind</i> of credential plus a label:
     * the label picks out an entry in {@code federation.security.static-tokens}, and
     * only the kind selects code. The console offers a suffix for
     * {@code static:} alone — {@code oauth2-jwt} and {@code oauth2-secret} read
     * their credentials from the endpoint row, so there is nothing for a label
     * to name — but a suffixed value may still reach here from a hand-edited
     * registry or an older import, and resolving it on the kind is what keeps
     * that working rather than throwing.
     *
     * <p>Note this splits on {@code ':'}, not {@code '-'}: the hyphen in
     * {@code oauth2-jwt} is part of the kind.
     */
    private static String kind(final String profile) {
        final int separator = profile.indexOf(':');
        return separator < 0 ? profile : profile.substring(0, separator);
    }
}
