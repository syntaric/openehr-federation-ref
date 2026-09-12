// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.identity.spi;

import com.syntaric.federation.query.EndpointDescriptor;

import java.util.List;
import java.util.Optional;

/**
 * mCSD-shaped addressing slot: resolves federation membership to addressable
 * endpoints. v1 backs this with the registry tables.
 */
public interface NodeAddressingService {

    List<EndpointDescriptor> activeMembers();

    Optional<EndpointDescriptor> byEndpointId(final String endpointId);
}
