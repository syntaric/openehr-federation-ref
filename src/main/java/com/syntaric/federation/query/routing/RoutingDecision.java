// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.routing;

import com.syntaric.federation.query.EndpointDescriptor;

import java.util.List;

/** Outcome of single-node route resolution. Rejections are thrown, not modelled. */
public sealed interface RoutingDecision {

    record SingleNode(EndpointDescriptor endpoint, String basis) implements RoutingDecision {
    }

    /** Reads only (N41 step 4 / N22 fallback): probe every member. */
    record AskAll(List<EndpointDescriptor> candidates) implements RoutingDecision {
    }
}
