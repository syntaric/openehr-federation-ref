// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.registry;

import com.syntaric.federation.api.error.FedErrorCode;
import com.syntaric.federation.api.error.FederationException;
import com.syntaric.federation.security.RegistrySecretCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lookups over the member registry plus learn-on-observe behaviour for
 * {@code system_id_mapping} and the {@code ehr_node_index}.
 */
@Service
public class RegistryService {

    private final OrganisationRepository organisations;
    private final NodeRepository nodes;
    private final EndpointRepository endpoints;
    private final SystemIdMappingRepository systemIdMappings;
    private final EhrNodeIndexRepository ehrNodeIndex;
    private final IntegrityIncidentRepository incidents;
    private final ResolutionBindingRepository resolutionBindings;
    private final NodeIdentifierRepository nodeIdentifiers;
    private final JdbcAggregateTemplate template;
    private final RegistrySecretCipher secretCipher;

    public RegistryService(final OrganisationRepository organisations,
                           final NodeRepository nodes,
                           final EndpointRepository endpoints,
                           final SystemIdMappingRepository systemIdMappings,
                           final EhrNodeIndexRepository ehrNodeIndex,
                           final IntegrityIncidentRepository incidents,
                           final ResolutionBindingRepository resolutionBindings,
                           final NodeIdentifierRepository nodeIdentifiers,
                           final JdbcAggregateTemplate template,
                           final RegistrySecretCipher secretCipher) {
        this.secretCipher = secretCipher;
        this.nodeIdentifiers = nodeIdentifiers;
        this.resolutionBindings = resolutionBindings;
        this.organisations = organisations;
        this.nodes = nodes;
        this.endpoints = endpoints;
        this.systemIdMappings = systemIdMappings;
        this.ehrNodeIndex = ehrNodeIndex;
        this.incidents = incidents;
        this.template = template;
    }

    // ---- lookups -------------------------------------------------------------

    public List<Organisation> allOrganisations() {
        return organisations.findAll();
    }

    public List<Node> allNodes() {
        return nodes.findAll();
    }

    public List<Endpoint> allEndpoints() {
        return endpoints.findAll();
    }

    public List<NodeIdentifier> allNodeIdentifiers() {
        return nodeIdentifiers.findAll();
    }

    /**
     * Maps a localizer-returned identifier to the node that owns it (N4).
     *
     * <p>Empty means the identifier names a community outside this federation —
     * routine for a national index, and the caller counts and audits it rather
     * than treating it as an error. More than one owner is a misconfigured
     * registry: this returns them all so the caller can say so, rather than
     * picking one and routing a clinical query at a coin flip.
     */
    public List<Node> nodesForIdentifier(final String system, final String value) {
        if (system == null || value == null || value.isBlank()) {
            return List.of();
        }
        return nodeIdentifiers.findBySystemAndValue(system, value).stream()
                .map(NodeIdentifier::nodeId)
                .distinct()
                .map(nodes::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    public List<Endpoint> activeEndpoints() {
        return endpoints.findByStatus("active");
    }

    public Optional<Endpoint> endpoint(final String endpointId) {
        return endpoints.findById(endpointId);
    }

    public Optional<Node> node(final String nodeId) {
        return nodes.findById(nodeId);
    }

    public Optional<Node> nodeBySystemId(final String systemId) {
        return nodes.findBySystemId(systemId);
    }

    public Optional<Organisation> organisation(final String id) {
        return organisations.findById(id);
    }

    /** Active endpoints belonging to any of the given organisation ids. */
    public List<Endpoint> endpointsForOrganisations(final Set<String> organisationIds) {
        return activeEndpoints().stream()
                .filter(e -> nodes.findById(e.nodeId())
                        .map(n -> n.organisationId() != null && organisationIds.contains(n.organisationId()))
                        .orElse(false))
                .toList();
    }

    /** Preferred (first) active endpoint of a node. */
    public Optional<Endpoint> endpointForNode(final String nodeId) {
        return endpoints.findByNodeId(nodeId).stream().filter(Endpoint::isActive).findFirst();
    }

    /**
     * Resolves a {@code creating_system_id} to its owning node: registry
     * ({@code node.system_id}) first, then learned {@code system_id_mapping}.
     */
    public Optional<Node> nodeForCreatingSystemId(final String creatingSystemId) {
        return nodes.findBySystemId(creatingSystemId)
                .or(() -> systemIdMappings.findById(creatingSystemId)
                        .flatMap(m -> nodes.findById(m.nodeId())));
    }

    public List<EhrNodeIndexEntry> ehrIndex(final String ehrId) {
        return ehrNodeIndex.findByEhrId(ehrId);
    }

    // ---- learn-on-observe ----------------------------------------------------

    @Transactional
    public void learnSystemId(final String creatingSystemId, final String nodeId, final String baseUrl) {
        if (systemIdMappings.existsById(creatingSystemId) || nodes.findBySystemId(creatingSystemId).isPresent()) {
            return;
        }
        template.insert(new SystemIdMapping(creatingSystemId, nodeId, baseUrl, SystemIdMapping.SOURCE_OBSERVED));
    }

    /**
     * Records that {@code ehrId} was observed on {@code nodeId}. If the same
     * {@code ehr_id} is already claimed by a different node this is a federation
     * integrity violation: an incident is raised and a 409 thrown.
     */
    @Transactional
    public void observeEhrOnNode(final String ehrId, final String nodeId) {
        final List<EhrNodeIndexEntry> existing = ehrNodeIndex.findByEhrId(ehrId);
        final boolean collision = existing.stream().anyMatch(e -> !e.nodeId().equals(nodeId));
        if (collision) {
            raiseEhrCollision(ehrId, nodeId, existing);
        }
        if (existing.stream().noneMatch(e -> e.nodeId().equals(nodeId))) {
            try {
                template.insert(new EhrNodeIndexEntry(null, ehrId, nodeId, Instant.now()));
            } catch (final DuplicateKeyException ignored) {
                // concurrent observation of the same (ehr_id, node_id) — benign
            }
        }
    }

    private void raiseEhrCollision(final String ehrId, final String nodeId, final List<EhrNodeIndexEntry> existing) {
        final String claimants = existing.stream().map(EhrNodeIndexEntry::nodeId).distinct().sorted()
                .reduce((a, b) -> a + "," + b).orElse("") + "," + nodeId;
        raiseCollisionIncident(ehrId, claimants);
        throw new FederationException(FedErrorCode.FED_EHR_ID_COLLISION,
                "ehr_id is claimed by more than one member node",
                Map.of("ehr_id", ehrId, "nodes", claimants));
    }

    /** Records an ehr_id collision as an integrity incident + audit alarm (N42). */
    public void raiseCollisionIncident(final String ehrId, final String claimants) {
        final String detail = "ehr_id=" + ehrId + " claimed by nodes [" + claimants + "]";
        incidents.save(new IntegrityIncident(null, IntegrityIncident.TYPE_EHR_ID_COLLISION, detail, Instant.now()));
    }

    // ---- admin CRUD (plan 3 §A1) ---------------------------------------------

    /** Endpoint dialect. Single-valued today, so the server sets it (§A3). */
    public static final String DEFAULT_CONNECTION_TYPE = "openehr-rest";

    @Transactional
    public Organisation createOrganisation(final String id, final String name) {
        requireAbsent(organisations.existsById(id), "Organisation", id);
        final Organisation created = template.insert(new Organisation(id, name));
        return created;
    }

    @Transactional
    public Organisation updateOrganisation(final String id, final String name) {
        requirePresent(organisations.findById(id), "Organisation", id);
        final Organisation updated = template.update(new Organisation(id, name));
        return updated;
    }

    @Transactional
    public void deleteOrganisation(final String id) {
        requirePresent(organisations.findById(id), "Organisation", id);
        final long nodeCount = nodes.findByOrganisationId(id).size();
        if (nodeCount > 0) {
            throw new RegistryInUseException("Organisation", id, Map.of("nodes", nodeCount));
        }
        organisations.deleteById(id);
    }

    @Transactional
    public Node createNode(final String nodeId, final String systemId, final String organisationId,
                           final String product, final String version) {
        requireAbsent(nodes.existsById(nodeId), "Node", nodeId);
        requireUnclaimedSystemId(systemId, nodeId);
        requireParentExists(organisationId);
        final Node created = template.insert(new Node(nodeId, systemId, organisationId, product, version));
        return created;
    }

    /**
     * Updates a node. {@code system_id} may change only while nothing has been
     * learned against the old value — a mapping or an EHR-index row means the
     * federation has already routed on it, and silently repointing it would
     * misroute every follow-up for an EHR already resolved to this node.
     */
    @Transactional
    public Node updateNode(final String nodeId, final String systemId, final String organisationId,
                           final String product, final String version) {
        final Node existing = requirePresent(nodes.findById(nodeId), "Node", nodeId);
        requireParentExists(organisationId);
        if (!existing.systemId().equals(systemId)) {
            requireUnclaimedSystemId(systemId, nodeId);
            requireSystemIdUnlearned(existing);
        }
        final Node updated = template.update(new Node(nodeId, systemId, organisationId, product, version));
        return updated;
    }

    @Transactional
    public void deleteNode(final String nodeId) {
        requirePresent(nodes.findById(nodeId), "Node", nodeId);
        final Map<String, Long> references = nonZero(Map.of(
                "endpoints", (long) endpoints.findByNodeId(nodeId).size(),
                "ehrIndexEntries", ehrNodeIndex.countByNodeId(nodeId),
                "resolutionBindings", resolutionBindings.countByNodeId(nodeId),
                "systemIdMappings", systemIdMappings.countByNodeId(nodeId)));
        // node_identifier is deliberately absent from that count. It is operator
        // configuration that cascades on delete, not learned data whose loss
        // would misroute anything — blocking on it would make a node undeletable
        // for the sole reason that someone told the registry its URA.
        if (!references.isEmpty()) {
            throw new RegistryInUseException("Node", nodeId, references);
        }
        nodes.deleteById(nodeId);
    }

    /**
     * The outbound-credential half of an endpoint write.
     *
     * <p>A separate parameter object rather than ten more positional arguments:
     * {@code createEndpoint} already takes eight, and adding ten same-typed
     * {@code String}s to that list is how a client id ends up in the scope
     * column with nothing to catch it.
     *
     * <p>{@code smartSigningKeyPem} and {@code oauthClientSecret} are
     * <b>plaintext in, ciphertext at rest</b> — this service encrypts. The
     * null-means-keep rule for them lives in {@link #applyCredentials}.
     */
    public record OutboundAuthSpec(
            String smartClientId,
            String smartTokenEndpoint,
            String smartSigningKeyPem,
            String smartScope,
            String oauthClientId,
            String oauthTokenEndpoint,
            String oauthClientSecret,
            String oauthScope,
            String oauthAuthMethod,
            String oauthAudience) {

        public static final OutboundAuthSpec NONE =
                new OutboundAuthSpec(null, null, null, null, null, null, null, null, null, null);
    }

    @Transactional
    public Endpoint createEndpoint(final String endpointId, final String nodeId, final String baseUrl,
                                   final String pixManagerUrl, final String authProfile, final String status) {
        return createEndpoint(endpointId, nodeId, baseUrl, pixManagerUrl, authProfile, status,
                OutboundAuthSpec.NONE);
    }

    @Transactional
    public Endpoint createEndpoint(final String endpointId, final String nodeId, final String baseUrl,
                                   final String pixManagerUrl, final String authProfile, final String status,
                                   final OutboundAuthSpec auth) {
        requireAbsent(endpoints.existsById(endpointId), "Endpoint", endpointId);
        if (nodes.findById(nodeId).isEmpty()) {
            throw new FederationException(FedErrorCode.FED_NOT_FOUND, "Unknown node '" + nodeId + "'");
        }
        // On create there is nothing to keep, so an absent secret means "none
        // configured" rather than "unchanged" — hence the null base row.
        final Endpoint blank = new Endpoint(endpointId, nodeId, baseUrl, pixManagerUrl,
                DEFAULT_CONNECTION_TYPE, authProfile, status, null,
                null, null, null, null, null, null, null, null, null, null);
        final Endpoint created = template.insert(applyCredentials(blank, auth));
        return created;
    }

    @Transactional
    public Endpoint updateEndpoint(final String endpointId, final String nodeId, final String baseUrl,
                                   final String pixManagerUrl, final String authProfile, final String status) {
        return updateEndpoint(endpointId, nodeId, baseUrl, pixManagerUrl, authProfile, status,
                OutboundAuthSpec.NONE);
    }

    /**
     * Updates an endpoint. {@code connection_type} and {@code latency_p50_ms} are
     * carried over rather than accepted: the first is server-owned (§A3), the
     * second is measured, and letting a form round-trip either would let an edit
     * quietly discard observed data.
     *
     * <p>Credentials are the opposite — operator-owned, so they are accepted —
     * with one asymmetry for the two secret fields: null means <b>keep the
     * stored value</b>, because the UI never holds a secret it can echo back
     * (M0's write-only contract), so "absent" is what an unchanged form sends.
     * An explicit empty string clears.
     */
    @Transactional
    public Endpoint updateEndpoint(final String endpointId, final String nodeId, final String baseUrl,
                                   final String pixManagerUrl, final String authProfile, final String status,
                                   final OutboundAuthSpec auth) {
        final Endpoint existing = requirePresent(endpoints.findById(endpointId), "Endpoint", endpointId);
        if (nodes.findById(nodeId).isEmpty()) {
            throw new FederationException(FedErrorCode.FED_NOT_FOUND, "Unknown node '" + nodeId + "'");
        }
        final Endpoint rebased = new Endpoint(endpointId, nodeId, baseUrl, pixManagerUrl,
                existing.connectionType(), authProfile, status, existing.latencyP50Ms(),
                existing.smartClientId(), existing.smartTokenEndpoint(),
                existing.smartSigningKeyEnc(), existing.smartScope(),
                existing.oauthClientId(), existing.oauthTokenEndpoint(),
                existing.oauthClientSecretEnc(), existing.oauthScope(),
                existing.oauthAuthMethod(), existing.oauthAudience());
        final Endpoint updated = template.update(applyCredentials(rebased, auth));
        return updated;
    }

    /**
     * Overlays an {@link OutboundAuthSpec} onto an endpoint row, encrypting the
     * two secrets.
     *
     * <p>The non-secret fields are plain assignment — the form carries their
     * current values, so what it sends is what they become. The secrets follow
     * the keep-when-absent rule described on {@link #updateEndpoint}.
     */
    private Endpoint applyCredentials(final Endpoint base, final OutboundAuthSpec auth) {
        if (auth == null) {
            return base;
        }
        return new Endpoint(base.endpointId(), base.nodeId(), base.baseUrl(), base.pixManagerUrl(),
                base.connectionType(), base.authProfile(), base.status(), base.latencyP50Ms(),
                auth.smartClientId(), auth.smartTokenEndpoint(),
                keepOrEncrypt(base.smartSigningKeyEnc(), auth.smartSigningKeyPem()),
                auth.smartScope(),
                auth.oauthClientId(), auth.oauthTokenEndpoint(),
                keepOrEncrypt(base.oauthClientSecretEnc(), auth.oauthClientSecret()),
                auth.oauthScope(), auth.oauthAuthMethod(), auth.oauthAudience());
    }

    /**
     * null → keep {@code storedCiphertext}; blank → clear; anything else →
     * encrypt it.
     *
     * <p>The null/blank distinction is the whole secret contract in one line, and
     * getting it backwards is the sharpest failure in this feature: treating
     * null as "clear" would make every ordinary edit of a source — a base URL
     * typo, a status flip — silently discard its credentials.
     */
    private String keepOrEncrypt(final String storedCiphertext, final String submittedPlaintext) {
        if (submittedPlaintext == null) {
            return storedCiphertext;
        }
        if (submittedPlaintext.isBlank()) {
            return null;
        }
        return secretCipher.encrypt(submittedPlaintext);
    }

    @Transactional
    public void deleteEndpoint(final String endpointId) {
        requirePresent(endpoints.findById(endpointId), "Endpoint", endpointId);
        endpoints.deleteById(endpointId);
    }

    // ---- node identifiers (N4 / CP-5) ----------------------------------------

    public List<NodeIdentifier> nodeIdentifiers(final String nodeId) {
        return nodeIdentifiers.findByNodeId(nodeId);
    }

    /**
     * Registers an external identifier for a node.
     *
     * <p>Rejects an identifier already claimed by a <i>different</i> node rather
     * than letting the pair coexist: the clinical lookup is
     * "who owns this identifier?", and two owners makes that question
     * unanswerable at exactly the moment a query depends on it. Re-registering
     * the same triple is a no-op, so an import can replay safely.
     */
    @Transactional
    public NodeIdentifier addNodeIdentifier(final String nodeId, final String system, final String value) {
        requirePresent(nodes.findById(nodeId), "Node", nodeId);
        final List<NodeIdentifier> claimants = nodeIdentifiers.findBySystemAndValue(system, value);
        final Optional<NodeIdentifier> mine = claimants.stream()
                .filter(i -> i.nodeId().equals(nodeId)).findFirst();
        if (mine.isPresent()) {
            return mine.get();
        }
        claimants.stream().findFirst().ifPresent(other -> {
            throw new RegistryConflictException("identifier '" + system + "|" + value
                    + "' is already claimed by node '" + other.nodeId() + "'");
        });
        final NodeIdentifier created = template.insert(new NodeIdentifier(null, nodeId, system, value));
        return created;
    }

    @Transactional
    public void deleteNodeIdentifier(final Long id) {
        requirePresent(nodeIdentifiers.findById(id), "Node identifier", String.valueOf(id));
        nodeIdentifiers.deleteById(id);
    }

    /** Counts from one {@link #importRegistry} call. */
    public record ImportCounts(int created, int updated) {
    }

    /**
     * Bootstraps or migrates a registry from the same JSON shape
     * {@code GET /admin/registry} returns (plan 3 §A2.1).
     *
     * <p>Idempotent per id: an existing row is updated, an unknown one inserted,
     * and rows absent from the document are <b>left alone</b>. Nothing is ever
     * deleted by an import — a document that happens to omit a source must not
     * silently drop it out of the federation, which is the failure mode that made
     * boot-time YAML reconciliation untenable in the first place.
     */
    @Transactional
    public ImportCounts importRegistry(final List<Organisation> importOrganisations,
                                       final List<Node> importNodes,
                                       final List<Endpoint> importEndpoints,
                                       final List<NodeIdentifier> importIdentifiers) {
        int created = 0;
        int updated = 0;
        for (final Organisation org : importOrganisations) {
            final boolean exists = organisations.existsById(org.id());
            upsert(new Organisation(org.id(), org.name()), exists);
            created += exists ? 0 : 1;
            updated += exists ? 1 : 0;
        }
        for (final Node node : importNodes) {
            final boolean exists = nodes.existsById(node.nodeId());
            requireParentExists(node.organisationId());
            upsert(new Node(node.nodeId(), node.systemId(), node.organisationId(),
                    node.product(), node.version()), exists);
            created += exists ? 0 : 1;
            updated += exists ? 1 : 0;
        }
        for (final Endpoint ep : importEndpoints) {
            final Optional<Endpoint> existing = endpoints.findById(ep.endpointId());
            if (nodes.findById(ep.nodeId()).isEmpty()) {
                throw new FederationException(FedErrorCode.FED_NOT_FOUND,
                        "Unknown node '" + ep.nodeId() + "' referenced by endpoint '" + ep.endpointId() + "'");
            }
            // Measured latency belongs to this deployment, not to the document.
            final Long p50 = existing.map(Endpoint::latencyP50Ms).orElse(null);
            final String status = ep.status() != null ? ep.status() : "active";
            final String authProfile = migrateLegacyProfile(ep.authProfile());
            // Secrets are NOT in the document — a serialised registry omits the
            // ciphertext columns (@JsonIgnore on Endpoint), so an imported
            // Endpoint always deserialises with them null. Carrying the stored
            // values across is what keeps that omission lossless: without it,
            // re-importing an export of your own registry would null every
            // outbound credential in it, and the federation would keep working
            // until each cached token expired.
            //
            // The non-secret credential fields ARE in the document and are
            // imported as given: they are operator configuration, and an export
            // must be able to carry "node A uses this client id at this token
            // endpoint" to another deployment.
            final String smartKeyEnc = existing.map(Endpoint::smartSigningKeyEnc).orElse(null);
            final String oauthSecretEnc = existing.map(Endpoint::oauthClientSecretEnc).orElse(null);
            upsert(new Endpoint(ep.endpointId(), ep.nodeId(), ep.baseUrl(), ep.pixManagerUrl(),
                    DEFAULT_CONNECTION_TYPE, authProfile, status, p50,
                    ep.smartClientId(), ep.smartTokenEndpoint(), smartKeyEnc, ep.smartScope(),
                    ep.oauthClientId(), ep.oauthTokenEndpoint(), oauthSecretEnc, ep.oauthScope(),
                    ep.oauthAuthMethod(), ep.oauthAudience()), existing.isPresent());
            created += existing.isPresent() ? 0 : 1;
            updated += existing.isPresent() ? 1 : 0;
        }
        for (final NodeIdentifier identifier : importIdentifiers) {
            if (nodes.findById(identifier.nodeId()).isEmpty()) {
                throw new FederationException(FedErrorCode.FED_NOT_FOUND,
                        "Unknown node '" + identifier.nodeId() + "' referenced by identifier '"
                                + identifier.system() + "|" + identifier.value() + "'");
            }
            // Matched on the natural key, not the surrogate id: an export from
            // another deployment carries that deployment's BIGSERIAL values, and
            // trusting them would either collide or create duplicates of a
            // triple the UNIQUE constraint already forbids.
            final boolean exists = !nodeIdentifiers
                    .findBySystemAndValue(identifier.system(), identifier.value()).stream()
                    .filter(existing -> existing.nodeId().equals(identifier.nodeId()))
                    .toList().isEmpty();
            if (!exists) {
                template.insert(new NodeIdentifier(null, identifier.nodeId(),
                        identifier.system(), identifier.value()));
                created++;
            } else {
                updated++;
            }
        }
        return new ImportCounts(created, updated);
    }

    /**
     * Rewrites the pre-rename auth profile names an older export still carries.
     *
     * <p>{@code smart} became {@code oauth2-jwt} and {@code oauth2} became
     * {@code oauth2-secret} — both were always OAuth2 {@code client_credentials}
     * grants, and the names now say which client authentication method each
     * uses. Stored rows are rewritten by migration {@code V11}, but an import
     * bypasses migrations entirely: a document exported before the rename and
     * imported after it would otherwise install a profile no provider answers
     * to, and every federated query to those endpoints would fail with "No
     * outbound auth provider for profile 'smart'".
     *
     * <p>Normalising here rather than rejecting is deliberate: the document is
     * operator configuration, and an import is a command to apply it, not an
     * exam. The suffix form is dropped for the
     * reason {@code OutboundAuthProviders} gives — it never selected anything.
     */
    private static String migrateLegacyProfile(final String authProfile) {
        if (authProfile == null) {
            return null;
        }
        if (authProfile.equals("smart") || authProfile.startsWith("smart:")) {
            return PROFILE_OAUTH2_JWT;
        }
        if (authProfile.equals("oauth2") || authProfile.startsWith("oauth2:")) {
            return PROFILE_OAUTH2_SECRET;
        }
        return authProfile;
    }

    /**
     * Duplicated from the two providers' {@code PROFILE} constants rather than
     * imported: {@code registry} is a leaf domain and must not depend on
     * {@code outbound} (ArchUnit {@code registryStaysALeaf}). These are stored
     * column values, so the registry owning its own copy of the vocabulary it
     * writes is the right side of that boundary; {@code AuthProfileNamesTest}
     * pins the two in agreement.
     */
    static final String PROFILE_OAUTH2_JWT = "oauth2-jwt";
    static final String PROFILE_OAUTH2_SECRET = "oauth2-secret";

    private void upsert(final Object aggregate, final boolean exists) {
        if (exists) {
            template.update(aggregate);
        } else {
            template.insert(aggregate);
        }
    }

    private static void requireAbsent(final boolean exists, final String kind, final String id) {
        if (exists) {
            throw new RegistryConflictException(kind + " '" + id + "' already exists");
        }
    }

    private static <T> T requirePresent(final Optional<T> found, final String kind, final String id) {
        return found.orElseThrow(() -> new FederationException(FedErrorCode.FED_NOT_FOUND,
                "Unknown " + kind.toLowerCase(java.util.Locale.ROOT) + " '" + id + "'"));
    }

    /** {@code node.system_id} is UNIQUE in the schema; report it as a conflict, not a 500. */
    private void requireUnclaimedSystemId(final String systemId, final String nodeId) {
        nodes.findBySystemId(systemId)
                .filter(other -> !other.nodeId().equals(nodeId))
                .ifPresent(other -> {
                    throw new RegistryConflictException(
                            "system_id '" + systemId + "' is already claimed by node '" + other.nodeId() + "'");
                });
    }

    private void requireSystemIdUnlearned(final Node existing) {
        final long learned = systemIdMappings.countByNodeId(existing.nodeId())
                + ehrNodeIndex.countByNodeId(existing.nodeId());
        if (learned > 0) {
            throw new RegistryConflictException("system_id of node '" + existing.nodeId()
                    + "' cannot change: the federation has already routed on it");
        }
    }

    private void requireParentExists(final String organisationId) {
        if (organisationId != null && !organisations.existsById(organisationId)) {
            throw new FederationException(FedErrorCode.FED_NOT_FOUND,
                    "Unknown organisation '" + organisationId + "'");
        }
    }

    private static Map<String, Long> nonZero(final Map<String, Long> counts) {
        return counts.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Admin: change an endpoint's status (active | offline | excluded). */
    @Transactional
    public Endpoint changeEndpointStatus(final String endpointId, final String status) {
        final Endpoint e = endpoints.findById(endpointId)
                .orElseThrow(() -> new FederationException(FedErrorCode.FED_NOT_FOUND,
                        "Unknown endpoint '" + endpointId + "'"));
        final Endpoint updated = new Endpoint(e.endpointId(), e.nodeId(), e.baseUrl(), e.pixManagerUrl(),
                e.connectionType(), e.authProfile(), status, e.latencyP50Ms(),
                e.smartClientId(), e.smartTokenEndpoint(), e.smartSigningKeyEnc(), e.smartScope(),
                e.oauthClientId(), e.oauthTokenEndpoint(), e.oauthClientSecretEnc(), e.oauthScope(),
                e.oauthAuthMethod(), e.oauthAudience());
        return endpoints.save(updated);
    }

    // ---- latency -------------------------------------------------------------
    //
    // recordLatency() used to live here and was called by FanOutExecutor on the
    // request thread — INSERT + findTop100 SELECT + endpoint UPDATE, per node per
    // query. That was the Invariant 0 breach M2 removes (plan 3 lines 86–96).
    //
    // endpoint.latency_p50_ms is registry data in this build: nothing here
    // aggregates observed latency into it, because that is a telemetry concern
    // this gateway does not implement. A deployment that measures supplies the
    // value; unmeasured, the column stays null and OPTIONS omits it rather than
    // reporting a misleading zero.
}
