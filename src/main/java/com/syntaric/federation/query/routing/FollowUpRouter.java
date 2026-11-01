// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.query.routing;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.query.EndpointDescriptor;
import com.syntaric.federation.registry.Node;
import com.syntaric.federation.registry.RegistryService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * N23/N36 control test for versioned writes. The node is located by
 * {@link EhrIdRouter} from the path {@code ehr_id}; this only decides whether
 * that node may take the write. It may if it is the controlling CDR, the one
 * whose {@code system_id} equals the target version's
 * {@code creating_system_id} (registry or learned mapping). Anything else is a
 * 409 naming the controlling system, raised before the write is forwarded, and
 * never a redirect to the owner: the path carries the holder's {@code ehr_id},
 * which means nothing there (spec 1.0.1, §12.4/§12.5.1).
 *
 * <p>Reads have no control test. A read located at a node holding an imported
 * copy returns that copy, and the client tells it apart by
 * {@code openEHR-federation-system-id} differing from the uid's
 * {@code creating_system_id} (§12.3).
 */
@Component
public class FollowUpRouter {

    private final RegistryService registry;

    public FollowUpRouter(final RegistryService registry) {
        this.registry = registry;
    }

    public void assertControls(final EndpointDescriptor located, final VersionUidParser.VersionUid uid) {
        final boolean controls = registry.nodeForCreatingSystemId(uid.creatingSystemId())
                .map(Node::nodeId)
                .filter(nodeId -> nodeId.equals(located.nodeId()))
                .isPresent();
        if (!controls) {
            throw new FederationException(FedErrorCode.FED_WRONG_CONTROLLING_SYSTEM,
                    "This versioned object is controlled by system '" + uid.creatingSystemId()
                            + "'; a write may only be applied there (N36)",
                    Map.of("controlling_system_id", uid.creatingSystemId()));
        }
    }
}
