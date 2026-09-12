# Changelog

Notable changes to this project. The version number tracks the specification
version this implements — see [Versioning](CONTRIBUTING.md#versioning).

## Unreleased

### Added

- Initial public release: a reference implementation of *Proposal for Federation
  Tier with AQL* v0.4.0 (https://github.com/syntaric/openehr-federation-spec).
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
