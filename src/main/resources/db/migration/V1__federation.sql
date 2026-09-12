-- The federation gateway's complete schema.
--
-- Table order is foreign-key order: organisation -> node -> endpoint ->
-- node_identifier -> system_id_mapping / ehr_node_index -> resolution_binding
-- -> integrity_incident.

-- ---------------------------------------------------------------------------
-- Member registry: organisations, CDR nodes, and their addressable endpoints.
-- ---------------------------------------------------------------------------

CREATE TABLE organisation (
    id          VARCHAR(128) PRIMARY KEY,
    name        VARCHAR(512) NOT NULL
);

CREATE TABLE node (
    node_id         VARCHAR(128) PRIMARY KEY,
    system_id       VARCHAR(512) NOT NULL UNIQUE,
    organisation_id VARCHAR(128) REFERENCES organisation (id),
    product         VARCHAR(256),
    version         VARCHAR(128)
);

-- Column order matches the component order of the Endpoint record: Spring Data
-- JDBC binds by name, but every construction site is positional, so the two are
-- kept in step deliberately.
CREATE TABLE endpoint (
    endpoint_id     VARCHAR(128) PRIMARY KEY,
    node_id         VARCHAR(128) NOT NULL REFERENCES node (node_id),
    base_url        VARCHAR(1024) NOT NULL,
    pix_manager_url VARCHAR(1024),
    connection_type VARCHAR(64)  NOT NULL DEFAULT 'openehr-rest',
    auth_profile    VARCHAR(128),
    status          VARCHAR(32)  NOT NULL DEFAULT 'active',
    latency_p50_ms  BIGINT,

    -- Per-endpoint outbound credentials.
    --
    -- These are registry data rather than one global config block, because a
    -- single outbound identity is wrong the moment nodes are separately
    -- operated — which is the premise of federating at all. A token minted by
    -- node A's authorization server is rejected by node B on iss/aud, or worse,
    -- accepted.
    --
    -- NULL in every column below means "no outbound credentials configured",
    -- which is exactly what the passthrough and static profiles want.

    -- SMART Backend Services / RFC 7523 (RS384 private_key_jwt assertion).
    smart_client_id         VARCHAR(256),
    -- The AS token endpoint, as published by the node operator. Required
    -- whenever the oauth2-jwt profile is selected.
    smart_token_endpoint    VARCHAR(1024),
    -- TEXT, not VARCHAR: an RSA PKCS#8 PEM is ~1.7KB before encryption.
    smart_signing_key_enc   TEXT,
    -- NULL means 'system/*.read system/*.write', the SMART Backend Services default.
    smart_scope             VARCHAR(512),

    -- Plain OAuth2 client credentials (RFC 6749 §2.3.1), for a node whose AS
    -- expects an ordinary client_id + client_secret grant and would reject a
    -- client assertion.
    oauth_client_id         VARCHAR(256),
    oauth_token_endpoint    VARCHAR(1024),
    oauth_client_secret_enc TEXT,
    -- Deliberately no default, unlike smart_scope: plain OAuth2 scopes are
    -- AS-specific, and a guessed value yields a token the AS accepts and the
    -- node then refuses — a failure that surfaces far from its cause.
    oauth_scope             VARCHAR(512),
    -- NULL means client_secret_basic; the alternative is client_secret_post.
    -- Authorization servers genuinely differ, and without the toggle a node
    -- whose AS accepts only one of the two cannot be federated.
    oauth_auth_method       VARCHAR(32),
    -- Some authorization servers will not mint a node-usable token without an
    -- audience / RFC 8707 resource parameter.
    oauth_audience          VARCHAR(1024)
);

CREATE INDEX idx_endpoint_node ON endpoint (node_id);

-- The _enc columns hold ciphertext produced by RegistrySecretCipher
-- ('v1:<base64 iv>:<base64 ciphertext>'), never a plaintext secret. Writing a
-- plaintext value here by hand — tempting while debugging — does not "work":
-- it fails the decrypt on first use, loudly and by design, because silently
-- treating an unreadable secret as absent would mean every federated query to
-- the endpoint goes out unauthorized.
COMMENT ON COLUMN endpoint.smart_signing_key_enc IS
    'AES-GCM ciphertext of the SMART signing key PEM. Never plaintext.';
COMMENT ON COLUMN endpoint.oauth_client_secret_enc IS
    'AES-GCM ciphertext of the OAuth2 client_secret. Never plaintext.';

-- ---------------------------------------------------------------------------
-- N4 / CP-5: external identifiers by which a localization service names a node.
-- ---------------------------------------------------------------------------
--
-- One mechanism serves every localizer rather than a column per backend: a
-- regional record locator answers with identifiers in its own namespace (a
-- care-provider number, an XCPD community OID), and this table maps them to
-- nodes. That is what lets a regional Annex B adapter be written without a
-- schema change.
--
-- A node may carry several identifiers of the same system (a node fronting two
-- communities is ordinary), so the key is the triple, not (node_id, system).
CREATE TABLE node_identifier (
    id       BIGSERIAL PRIMARY KEY,
    node_id  VARCHAR(128) NOT NULL REFERENCES node (node_id) ON DELETE CASCADE,
    system   VARCHAR(256) NOT NULL,
    value    VARCHAR(256) NOT NULL,
    UNIQUE (system, value, node_id)
);

-- The lookup is always "who owns this identifier?", never "what does this node
-- have?": the localizer hands back identifiers and the federation must map them
-- to nodes, once per query, on the clinical path.
CREATE INDEX idx_node_identifier_lookup ON node_identifier (system, value);

-- ---------------------------------------------------------------------------
-- Follow-up routing: creating_system_id -> node mapping and the ehr_id index.
-- ---------------------------------------------------------------------------

CREATE TABLE system_id_mapping (
    creating_system_id VARCHAR(512) PRIMARY KEY,
    node_id            VARCHAR(128) NOT NULL REFERENCES node (node_id),
    base_url           VARCHAR(1024) NOT NULL,
    source             VARCHAR(32) NOT NULL DEFAULT 'registry' -- registry | observed
);

-- An ehr_id claimed by more than one node is a federation integrity violation
-- (409 + integrity incident, N42); the unique constraint is on (ehr_id, node_id)
-- so the service layer can detect the collision and raise the incident.
CREATE TABLE ehr_node_index (
    id          BIGSERIAL PRIMARY KEY,
    ehr_id      VARCHAR(128) NOT NULL,
    node_id     VARCHAR(128) NOT NULL REFERENCES node (node_id),
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (ehr_id, node_id)
);

CREATE INDEX idx_ehr_node_index_ehr ON ehr_node_index (ehr_id);

-- ---------------------------------------------------------------------------
-- Patient resolution cache.
-- ---------------------------------------------------------------------------
--
-- Stores only an HMAC-SHA256 hash of the patient reference — never the raw
-- identifier value (spec N33).
CREATE TABLE resolution_binding (
    id               BIGSERIAL PRIMARY KEY,
    patient_ref_hash VARCHAR(64)  NOT NULL,
    node_id          VARCHAR(128) NOT NULL REFERENCES node (node_id),
    local_ehr_id     VARCHAR(128),
    consent_status   VARCHAR(32)  NOT NULL DEFAULT 'permitted', -- permitted | denied | unknown
    resolved_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    UNIQUE (patient_ref_hash, node_id)
);

CREATE INDEX idx_resolution_binding_hash ON resolution_binding (patient_ref_hash);

-- ---------------------------------------------------------------------------
-- Federation integrity incidents (N42).
-- ---------------------------------------------------------------------------
--
-- Written by RegistryService.raiseCollisionIncident when one ehr_id is claimed
-- by two nodes. Spec-mandated: the collision is a property of the federation,
-- not of the request that happened to surface it, so it outlives that request.
CREATE TABLE integrity_incident (
    id         BIGSERIAL PRIMARY KEY,
    type       VARCHAR(64)  NOT NULL,
    detail     TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
