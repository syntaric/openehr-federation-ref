-- ---------------------------------------------------------------------------
-- §12.7 / N44: the gateway-held stored-query registry.
-- ---------------------------------------------------------------------------
--
-- The gateway is authoritative for a federated stored query: a definition is
-- PUT here once and afterwards invoked by name, at which point it is expanded
-- into an ordinary fan-out. Definitions are NOT distributed to member nodes.
--
-- A stored version is immutable. The UNIQUE constraint on (name, version) is
-- what makes that race-safe: two concurrent PUTs of the same pair cannot both
-- succeed, whatever the service layer pre-checked. The surrogate id exists
-- because Spring Data JDBC has no composite @Id (same pattern as
-- resolution_binding); the natural key is the constraint.
--
-- Holds AQL text only — never a patient identifier. A definition whose subject
-- predicate is a literal instead of a $parameter is refused before it gets here.
CREATE TABLE stored_query (
    id        BIGSERIAL    PRIMARY KEY,
    name      VARCHAR(256) NOT NULL,   -- [{namespace}::]{query-name}, ITS-REST form
    version   VARCHAR(64)  NOT NULL,   -- strict semver x.y.z, ITS-REST's own path segment
    type      VARCHAR(16)  NOT NULL DEFAULT 'AQL',
    aql       TEXT         NOT NULL,
    saved_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (name, version)
);

-- Invocation without a version resolves the latest, so the lookup is by name.
CREATE INDEX idx_stored_query_name ON stored_query (name);
