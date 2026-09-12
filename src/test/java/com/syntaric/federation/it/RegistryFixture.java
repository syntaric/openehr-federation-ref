// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.it;

import com.syntaric.federation.registry.Endpoint;
import com.syntaric.federation.registry.EndpointRepository;
import com.syntaric.federation.registry.Node;
import com.syntaric.federation.registry.NodeIdentifier;
import com.syntaric.federation.registry.NodeIdentifierRepository;
import com.syntaric.federation.registry.NodeRepository;
import com.syntaric.federation.registry.Organisation;
import com.syntaric.federation.registry.OrganisationRepository;
import com.syntaric.federation.registry.SystemIdMapping;
import com.syntaric.federation.registry.SystemIdMappingRepository;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts registry rows for integration tests.
 *
 * <p>Replaces the {@code federation.registry.*} {@code @DynamicPropertySource} seeding
 * the ITs used to rely on. YAML seeding is gone (plan 3 §A2): the database is the
 * single source of truth, so tests now populate it the same way the admin API
 * does rather than through a config path that no longer exists in production.
 *
 * <p>Registering the {@code system_id -> node} mapping alongside the node is not
 * incidental — {@code RegistrySeeder} used to write that row too, and follow-up
 * routing resolves {@code creating_system_id} through it. Omitting it here would
 * leave routing ITs failing for a reason that has nothing to do with what they
 * test.
 */
@Component
public class RegistryFixture {

    private final OrganisationRepository organisations;
    private final NodeRepository nodes;
    private final EndpointRepository endpoints;
    private final SystemIdMappingRepository systemIdMappings;
    private final NodeIdentifierRepository nodeIdentifiers;
    private final JdbcAggregateTemplate template;

    public RegistryFixture(OrganisationRepository organisations,
                           NodeRepository nodes,
                           EndpointRepository endpoints,
                           SystemIdMappingRepository systemIdMappings,
                           NodeIdentifierRepository nodeIdentifiers,
                           JdbcAggregateTemplate template) {
        this.organisations = organisations;
        this.nodes = nodes;
        this.endpoints = endpoints;
        this.systemIdMappings = systemIdMappings;
        this.nodeIdentifiers = nodeIdentifiers;
        this.template = template;
    }

    /** Upserts an organisation. */
    @Transactional
    public void organisation(String id, String name) {
        upsert(new Organisation(id, name), organisations.existsById(id));
    }

    /**
     * Upserts a node, its single endpoint and the {@code system_id} mapping —
     * the whole "one source" unit an IT normally wants.
     */
    @Transactional
    public void source(String organisationId, String nodeId, String systemId, String baseUrl) {
        source(organisationId, nodeId, systemId, baseUrl, "active", null, null);
    }

    /** Full form: explicit status, product/version, for tests that assert on them. */
    @Transactional
    public void source(String organisationId, String nodeId, String systemId, String baseUrl,
                       String status, String product, String version) {
        upsert(new Node(nodeId, systemId, organisationId, product, version), nodes.existsById(nodeId));
        Long existingP50 = endpoints.findById(nodeId).map(Endpoint::latencyP50Ms).orElse(null);
        upsert(Endpoint.withoutCredentials(nodeId, nodeId, baseUrl, null, "openehr-rest", null,
                        status, existingP50),
                endpoints.existsById(nodeId));
        if (systemId != null) {
            upsert(new SystemIdMapping(systemId, nodeId, baseUrl, SystemIdMapping.SOURCE_REGISTRY),
                    systemIdMappings.existsById(systemId));
        }
    }

    /**
     * Registers an external identifier for a node — the URA or XCPD community
     * OID a localization service will answer with (N4 / CP-5).
     *
     * <p>Idempotent on the natural triple rather than the surrogate id, so an IT
     * that re-seeds per test (which every IT here does) does not accumulate
     * duplicate rows or trip the UNIQUE constraint on the second run.
     */
    @Transactional
    public void identifier(String nodeId, String system, String value) {
        boolean exists = nodeIdentifiers.findBySystemAndValue(system, value).stream()
                .anyMatch(i -> i.nodeId().equals(nodeId));
        if (!exists) {
            template.insert(new NodeIdentifier(null, nodeId, system, value));
        }
    }

    /** Drops every identifier of a node, for tests that assert the unmapped case. */
    @Transactional
    public void clearIdentifiers(String nodeId) {
        nodeIdentifiers.deleteAll(nodeIdentifiers.findByNodeId(nodeId));
    }

    /**
     * Removes a source and everything hanging off it, if present.
     *
     * <p>For tests whose <i>subject</i> is the write API: as {@code registry-admin}
     * those writes genuinely execute against the Postgres the whole IT suite
     * shares, so a throwaway endpoint created to prove a matcher lets admins
     * through stays {@code active} in the registry afterwards — and joins the
     * ask-all probe of every routing test that runs later, where it is
     * unreachable and can claim an {@code ehr_id} the test expected a WireMock
     * node to own. Re-seeding the three real sources does not undo that; only
     * deleting the extra one does.
     */
    @Transactional
    public void removeSource(String endpointId, String nodeId) {
        endpoints.findById(endpointId).ifPresent(endpoint -> endpoints.deleteById(endpointId));
        nodes.findById(nodeId).ifPresent(node -> {
            systemIdMappings.deleteAll(systemIdMappings.findByNodeId(nodeId));
            nodeIdentifiers.deleteAll(nodeIdentifiers.findByNodeId(nodeId));
            nodes.deleteById(nodeId);
        });
    }

    /** Removes an organisation if present. Nodes must already be gone. */
    @Transactional
    public void removeOrganisation(String id) {
        organisations.findById(id).ifPresent(organisation -> organisations.deleteById(id));
    }

    private void upsert(Object aggregate, boolean exists) {
        if (exists) {
            template.update(aggregate);
        } else {
            template.insert(aggregate);
        }
    }
}
