# Changelog

Notable changes to this project. The version number tracks the specification
version this implements — see [Versioning](CONTRIBUTING.md#versioning).

## Unreleased

Tracks the specification's three commits after 0.9.0, up to the SEC review
amendments of 2026-09-28. **The spec changed the wire contract without moving
`spec_version`** — it stays `"0.9"` and the spec owes a bump before release —
so a client cannot detect the changes below from `OPTIONS {base}/`; this entry
is the notice.

### Changed

- **Breaking wire change: the federation's `meta` additions are nested under
  `meta.federation`** (§9.1, N17, CP-35). `complete`, `endpoints`, `timeout`
  and `dedup` moved from direct members of `meta` to members of one unprefixed
  `federation` object; the flat form is now a CP-35 failure. `dedup` gained the
  schema's `suppressed_rows` and `suppressed_endpoints[]` beside this gateway's
  per-row `suppressed[]`. The envelope gained ITS-REST's `name`, emitted for a
  stored query and omitted otherwise. The vendored schemas were refreshed from
  the spec working tree on 2026-09-28.
- **All-or-nothing is the default completion strategy** (§11.4, N37, CP-30).
  Previously best-effort was the default and all-or-nothing an opt-in. Now an
  in-scope node that was asked and did not answer fails the query with
  `504 FED_INCOMPLETE`, and one that answered with an error fails it with the
  new `424 FED_NODE_ERROR`; when one fan-out has both, `504` is reported. The
  failing body still carries `meta.federation` with every endpoint's status, as
  §11.4 requires. `not-resolved` and `consent-denied` clear `complete` but never
  fail (§11.3). Best-effort is opt-in with
  `openEHR-federation-completeness: partial`; `all` is accepted explicitly; any
  other value, and `partial` where it is not offered, is `400`. A node `403`
  after dispatch is a node error, never `consent-denied` — that status is
  claimed only by the Step-1 pre-filter.
- **`OPTIONS {base}/` no longer declares targeting mechanisms.** `aql` carries
  `fan_out` only; `endpoint_directive` and `endpoint_header` are gone from body
  and schema, since both are mandatory under N35. `timeout.policy` is
  `all-or-nothing`; `completeness` is `{default: "all-or-nothing", best_effort,
  opt_in}` with `opt_in` present only when offered; `definition` gained
  `stored_query_registry: true` and `stored_query_fan_out: false`;
  `its_rest.definition` reads `routed-single-node; stored queries at the
  gateway registry`.
- `DefinitionController` routes `/v1/definition/template/**` only; the
  stored-query sub-area is the gateway's own (below).
- `POST /v1/query/aql` now binds `query_parameters`. They were accepted and
  silently dropped.

### Fixed

- An unreachable member — a refused connection, as opposed to a slow or
  erroring one — was reported `offline` with **no `error` member**, which the
  published envelope schema forbids. The JDK client's refused-connection
  exception carries an empty message and it was copied straight through.
  Found against a stopped demo node; every IT had used a delayed or a 500
  stub. `offline` entries now always carry a reason, and an IT dispatches to
  a dead port to keep it that way.
- **Both patient-identifier carriers are accepted as resolution input** (spec
  §5.4.3, new CP-38, Track 2). A query may name the patient through
  `e/ehr_status/subject/external_ref/id/value` *or* through an `ENTRY`-level
  `subject` `PARTY_IDENTIFIED`/`DV_IDENTIFIER` predicate —
  `o/subject/identifiers/id`, `c/content[…]/subject/identifiers/id` — and the
  gateway resolves on whichever was used. Both forms rewrite to the same
  `ehr_id`-keyed node AQL; neither reaches a node. An earlier spec draft made
  this a MAY with an `OPTIONS` declaration (`federation.resolution_input`);
  that was withdrawn before it was implemented here, and nothing is declared.
- **The identifier guard is value-based, not path-based.** A predicate over
  `COMPOSITION.composer`, `EVENT_CONTEXT.health_care_facility`,
  `PARTICIPATION.performer` or `ATTESTATION.committer` identifies a clinician
  or facility, not the patient, and is now dispatched as ordinary query
  material — §5.4.3 states a gateway MUST NOT refuse it on its path. What is
  still refused, `400 FED_IDENTIFIER_UNSTRIPPABLE`: any such predicate whose
  literal *is* the patient identifier the query resolves on (the smuggling
  case, still backstopped by the string-level hygiene gate), an `ENTRY`-level
  `subject` path resolution did not consume — a second value, an `ORDER BY`, a
  `subject/name` — and the non-canonical `external_ref/id` forms. A
  `PARTY_RELATED` subject is indistinguishable from `PARTY_IDENTIFIED` by
  path and is handled as the patient carrier: consumed when it is the
  resolution input, rejected otherwise. The golden corpus case that pinned
  `c/composer/identifiers/id = '<clinician>'` as a rejection now pins its
  dispatch.
- **The issuing namespace is derived, not constant.** `PatientToken` was built
  with the literal `"facade"` for every query; §5.2 resolves the identifier
  *with its issuing namespace*, and §5.4.3 names the source per carrier. The
  gateway now consumes `external_ref/namespace` beside the canonical predicate
  and `identifiers/issuer` (falling back to `identifiers/type`) beside an
  `ENTRY`-level `subject`, strips them from the dispatched AQL — a node is not
  required to hold the identifier at all, so a namespace predicate left in
  place would filter on data it may not have — and falls back to the new
  `federation.identity.default-namespace` when the client supplies none. That
  fallback defaults to `facade`, so existing `resolution_binding` hashes and
  PIXm `sourceIdentifier` values are unchanged until a deployment sets it.

### Added

- **A gateway-held stored-query registry** (§12.7, N44, new CP-40, Track 9).
  `PUT /v1/definition/query/{name}/{version}` stores a definition at the
  gateway (`text/plain` or JSON `{q}`); `GET` reads one version or the ITS-REST
  `{versions: […]}` list; `GET|POST /v1/query/{name}[/{version}]` expands it
  into the ordinary fan-out with the client's `query_parameters` bound in, and
  answers under its ITS-REST `name`. Versions are strict semver and immutable —
  a second `PUT` to the same pair is `409 FED_STORED_QUERY_EXISTS`. A definition
  must name the patient through a `$parameter`: one with a literal on a subject
  path is refused `400 FED_IDENTIFIER_HYGIENE`, because the registry outlives
  the request and N33 forbids the gateway to hold an identifier at rest.
  Definitions are not distributed to nodes. New package `definition` (a leaf,
  pinned by ArchUnit), migration `V2__stored_query.sql`, and the
  `FED_STORED_QUERY_INVALID` / `FED_QUERY_PARAMETER_INVALID` codes.
- `QueryParameterBinder`: `$parameters` are bound into the parsed AST as typed
  primitives the SDK renderer escapes — never spliced into text — *before*
  subject analysis, so a bound `$patient_id` is rewritten to an `ehr_id` scope
  like a literal. Rejections name the parameter and never echo the value.
- `federation.completeness.offer-best-effort` (default `true`): whether
  `partial` is honoured. Off, it is rejected — the spec forbids ignoring it —
  and `OPTIONS` says `best_effort: false`.
- `NodeOutcome.failure` (`UNREACHABLE | TIMEOUT | NODE_ERROR`) and
  `NodeHttpErrorException`, so the fan-out can tell "did not answer" from
  "answered with an error" — the difference between §11.4's `504` and `424` —
  while the wire status stays inside §11.1's closed vocabulary.
- `CompletionPolicy`, the pure §11.1/§11.4 decision table, and
  `IncompleteFederationException` carrying the envelope a failing response must
  keep. `OpenEhrErrorBody` gained a `meta` member for it.
- `docs/security.md`, answering each §13.4 obligation for this build (new
  CP-39, N25), with `SecurityDecisionsDocumentedTest` asserting it does. CP-39
  is scored as documentation, so this repository can carry it.
- Conformance matrix denominators: CP-1..CP-40, N1..N44.
- `federation.identity.default-namespace` — the issuing namespace assumed for a
  patient identifier presented without one (see *Changed*).
- CP-38 in the conformance matrix (N33), with `AdversarialHygieneTest` now also
  carrying `TRACK-10`, which its javadoc had claimed since the start.
- The vendored `options-root.schema.json` was refreshed from the spec 0.9.0
  working tree as of 2026-09-14 (unchanged apart from a trailing newline: the
  `resolution_input` property came and went between refreshes), and both
  schemas again on 2026-09-28 for the SEC amendments.

- Initial public release: a reference implementation of *Proposal for Federation
  Tier with AQL* v0.9.0 (https://github.com/syntaric/openehr-federation-spec).
  Development ran against spec 0.4.0 and the findings below are recorded against
  that version; the spec moved to 0.9.0 — release candidate, no wire change — on
  the strength of having been implemented here, and this gateway targets 0.9.0.
  `federation.spec_version` accordingly reports `"0.9"` rather than `"0.4"`.
- **The result envelope is an openEHR ITS-REST `RESULT_SET`** (Release-1.1.0), and
  is now documented and tested as one rather than as a look-alike (§9.1). Three
  consequences, all of which changed the wire output during development against
  spec 0.4.0:
  - `rows` entries are **ordered arrays**, not objects keyed by column name:
    `rows[n][i]` is the value of `columns[i]`. An earlier build emitted objects,
    matching an example in spec §9.3 that contradicted the standard it claimed
    conformance to — the spec example was what was wrong, and spec 0.4.0 corrects
    it (finding F3).
  - `columns[]` entries carry `name` and `path` only. ITS-REST defines no `type`
    member on `RESULT_SET_COLUMN`; a gateway conveying a type does so in the row
    value, as openEHR's own `{"_type": "DV_TEXT", …}` form.
  - The federation's `meta` additions are never `_`-prefixed — that prefix is
    reserved to openEHR's own fields, and `additionalProperties: true` on
    `ResultSetMetadata` is what makes the additions conformant rather than a
    deviation from N1.
- `SpecSchemaConformanceIT` validates this gateway's AQL envelope and `OPTIONS`
  body against the JSON Schemas the specification publishes
  (`federated-result-set.schema.json`, `options-root.schema.json`), vendored under
  `src/test/resources/spec-schemas/`. The schemas *are* the contract, so this is
  the cheapest conformance check available — and it is the one that would have
  caught the row-shape defect above automatically.
- `NodeOutcome.dispatched()`, distinguishing "a query was sent to this node" from
  `inScope()`'s "node selection intended to ask it". `meta.endpoints[].latency_ms`
  follows dispatch, not scope: it is omitted for `excluded`, `not-localized`,
  `not-resolved` and a pre-filtered `consent-denied`, because there is no elapsed
  time to report for a request that was never made and a `0` would read as
  "answered instantly" (§9.5, N40). `not-resolved` is the case that separates the
  two predicates — in scope, so it clears `meta.complete`, but never dispatched to.
- AQL fan-out with subject-to-`ehr_id` rewriting, result merge, and per-node
  provenance in `meta.endpoints`.
- Byte-identical `/v1/ehr/**` proxy with follow-up routing on `ehr_id` and
  `creating_system_id`, and integrity incidents on an `ehr_id` collision (N42).
- `OPTIONS {base}/` federation self-description (N30).
- Outbound OAuth2: RFC 7523 private-key JWT client assertion (`oauth2-jwt`) and
  RFC 6749 client secret (`oauth2-secret`), configured per endpoint.
- Patient localization SPI (N4/§14) with the conformant ask-all fallback and two
  documented stubs for regional Annex B bindings (NVI, MITZ).
- File-based registry bootstrap (`federation.registry.bootstrap-file`).
- Conformance matrix covering CP-1..CP-37 from spec §17, plus N- and
  track-coverage derived from the spec's own CP-to-requirement mapping.
- Per-query endpoint status `not-localized` (§11.1) for a member localization did
  not name, distinct from `excluded`, which records that some authority ruled the
  node out. Neither clears `meta.complete`, since a node that was never in scope
  cannot make a query incomplete (§11.1 *What "in scope" means*, N37).
- `OPTIONS {base}/` declares every behaviour N30 requires declared: JWKS location
  (`federation.federation.jwks-uri`, §13.1), localizer-failure policy (§14.1),
  all-or-nothing completeness, OFFSET strategy, decomposable aggregates and
  fan-out template upload. Endpoint entries carry `node_id`, and `product` and
  `version` as separate fields (§7a.2).
