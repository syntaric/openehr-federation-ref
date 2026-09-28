# Architecture

The gateway is one Spring Boot application over one Postgres database. It holds
no clinical data: every record it returns was fetched from a member node during
the request that returned it.

## Packages

All under `com.syntaric.federation`:

| Package | Responsibility |
|---|---|
| `api` | HTTP surface: `/v1/query/**`, `/v1/ehr/**`, `/v1/definition/**`, `OPTIONS {base}/`, error rendering |
| `query` | The federation engine — see below |
| `query.aql` | Parse, bind parameters, analyse, rewrite and merge AQL |
| `query.fanout` | Parallel dispatch and the timeout budget |
| `query.routing` | Follow-up routing: which node owns this `ehr_id` or version UID |
| `identity` | Resolving a subject to per-node `ehr_id`s, and the localization SPI |
| `registry` | Members: organisations, nodes, endpoints, identifiers |
| `definition` | The stored-query registry: named, immutably versioned AQL text (§12.7) |
| `outbound` | HTTP clients to member nodes, and outbound auth profiles |
| `security` | Outbound credential encryption and its boot-time validation |
| `config` | Properties and startup wiring |

## Enforced boundaries

Six rules in `ArchitectureTest` hold the shape. They exist because each one
guards a mistake that reads as a reasonable simplification at the point someone
makes it.

| Rule | Forbids |
|---|---|
| `identitySpiDependsOnNothingInternal` | The SPI reaching into internals, so it stays extractable |
| `nothingDependsOnControllers` | Depending back on the HTTP layer |
| `registryStaysALeaf` | `registry` reaching into query, outbound or identity |
| `definitionStaysALeaf` | The stored-query registry reaching into the engine, or into the AQL SDK at all |
| `aqlPipelineIsPure` | The AQL pipeline touching the database or HTTP |
| `fanOutReachesNoPersistence` | Any persistence at all on the fan-out path |

> **If you move a package, re-check that these still bite.** Most are
> `noClasses().that()` rules, which pass *vacuously* when their `that()` clause
> matches nothing — so a pattern that silently stops matching does not fail, it
> just stops enforcing. The patterns are written as absolute package names
> rather than `..relative..` ones for exactly this reason. To verify, introduce
> the violation a rule forbids and confirm it fails.

### Invariant 0: nothing slow or stateful on the clinical path

`fanOutReachesNoPersistence` is the structural form of a rule worth stating
outright: **a federated query must not do synchronous database work.**

This is a regression guard, not a hypothetical. Latency sampling once called
into the registry from the dispatch thread — an INSERT, a SELECT and an UPDATE
per node per query. It was exception-wrapped, so it satisfied "never fail", and
nothing noticed it violated "never delay".

Nothing on this path records anything. Per-node latency and status leave with
the response in `meta.federation.endpoints` (N40) and are not persisted;
request tracking and telemetry are deployment concerns the specification does
not define, and a fork that wants them adds them where it wants the cost.

## Request flow

### Federated AQL — `POST /v1/query/aql`, `/v1/query/{name}`

1. **Parse, bind, analyse.** ITS-REST `query_parameters` are bound into the
   parsed AST — every `$name` becomes a typed primitive the SDK renderer
   escapes, so nothing is ever spliced into text — *before* subject analysis,
   so a `$patient_id` on the subject path is seen as the subject it is. Then
   reject what cannot be answered correctly across a federation rather than
   answering it wrongly: `OFFSET > 0` (N39, §11.6.2), aggregates that are not
   decomposable, a subject predicate under `OR`.
2. **Resolve targets.** Explicit endpoints or organisations from the
   `openEHR-federation-*` request headers, otherwise every active member.
3. **Localize** (N4, §14). A regional record locator narrows the candidate set.
   A node it does not name is reported `excluded` and is **never contacted** —
   that is the privacy property, and a "skipped" node that still receives the
   query has already learned what localization exists to hide.
4. **Cross-reference.** Resolve the subject to each candidate node's local
   `ehr_id`.
5. **Rewrite per node.** The subject predicate becomes `e/ehr_id/value = '<that
   node's id>'`. An identifier hygiene gate then re-checks the outgoing AQL and
   URL against the identifiers that must not appear in them, and refuses
   dispatch if any survives. Rewriting is where a leak would be introduced;
   the gate is where it is caught.
6. **Fan out** in parallel under the timeout budget.
7. **Decide** (`CompletionPolicy`, §11.4). All-or-nothing is the default: an
   in-scope node that did not answer fails the query with `504`, one that
   answered with an error with `424`. Both carry `meta.federation` in the
   error body — the envelope is built *before* the decision so a failure is
   diagnosable. `not-resolved` and `consent-denied` clear `complete` but never
   fail (§11.3). A client that opted into `partial` gets the rows that arrived.
8. **Merge.** Reconcile columns across nodes, apply `ORDER BY` and `LIMIT`
   across the merged set, and attach provenance.

The response is 200 with `meta.federation.endpoints` — one entry per node, each
with a status and a latency (N40) — and `meta.federation.complete`. A partial
federation is a failed request unless the client said it would take a partial
*result*.

### Stored queries — `/v1/definition/query/**`, `/v1/query/{name}`

The gateway holds federated stored queries in its own registry (§12.7, N44)
rather than routing them to one node as it does templates. A definition is
`PUT` once under `{name}/{version}` — ITS-REST's own semver segment — and is
immutable from then on: a second `PUT` to the same pair is `409`, and changing a
definition means a new version, which is what makes a result reproducible.

Invoking it by name expands the stored text into the pipeline above, exactly as
if the client had sent it inline; the only difference is the ITS-REST `name`
member in the envelope, which N44 requires so a client can see which definition
answered. Definitions are **not** distributed to member nodes (declared in
`OPTIONS` as `stored_query_fan_out: false`), so §12.7's drift hazard does not
arise here.

Two rules at the door. A definition names the patient through a `$parameter`,
never a literal — the registry outlives the request, and N33 forbids the
gateway to hold a patient identifier at rest — and the `definition` package
holds AQL *text* only, with no dependency on the AQL SDK, so nothing in it can
execute a query by a path that skipped the hygiene gate.

### Direct access — `/v1/ehr/**`

Proxied byte-identically to the owning node. Requests and responses are passed
through unchanged, because a gateway that reformats a composition is no longer
transparent and the client can no longer verify what the node actually said.

Ownership is resolved by `query.routing`: from the `ehr_id` index, or from the
`creating_system_id` embedded in a version UID. Observations are learned as they
happen — a node answering for an `ehr_id` teaches the index that it owns it.

An `ehr_id` claimed by two nodes is a federation integrity violation: it raises
an `integrity_incident` and answers `FED_EHR_ID_COLLISION` (N42). The incident
outlives the request that surfaced it, because the problem belongs to the
federation rather than to that one query.

## Identifier hygiene

The specification is strict about what may cross a node boundary (N33, §14.4),
and the gateway enforces it in two places:

- **Rewriting** replaces the subject predicate before dispatch, so a member node
  receives its own `ehr_id` and never the patient identifier. The patient may be
  named through either carrier §5.4.3 makes mandatory —
  `e/ehr_status/subject/external_ref/id/value` or an `ENTRY`-level `subject`
  `PARTY_IDENTIFIED`/`DV_IDENTIFIER` predicate such as
  `o/subject/identifiers/id` — and both rewrite to the same node AQL. A
  namespace predicate beside either (`external_ref/namespace`,
  `identifiers/issuer`) is consumed as the identifier's issuing namespace and
  stripped with it.
- **The analysis guard** judges every other identifier-bearing path by its
  *value*, not its shape: `c/composer/identifiers/id = '<clinician>'` is
  ordinary query material and is dispatched, while the same path compared to
  the patient identifier the query resolves on is refused
  (`FED_IDENTIFIER_UNSTRIPPABLE`), as is any `ENTRY`-level `subject` path
  resolution did not consume.
- **The hygiene gate** re-checks the rewritten AQL and URL against a set of
  forbidden values and refuses dispatch (`FED_IDENTIFIER_HYGIENE`) rather than
  sending something it cannot vouch for.

`resolution_binding` stores only an HMAC of the patient reference. The raw
identifier is never persisted.

Nothing else persists a request at all — there is no audit trail here to leak
into, which removes a whole class of N33 exposure rather than redacting it.

## Localization is a filter, not a gate

A localization result names **candidates**. Presence does not mean consent was
granted, and absence does not mean it was refused (§14.4).

- A node **absent** from the result holds no records → `excluded`.
- A node a *consent-aware* localizer **explicitly refused** → `consent-denied`
  (N27a).

Collapsing these would report a decision no consent authority made. And a
localized node may still refuse on its own: under N27 each node is the consent
authority for its own records, so localization filters *in front of* that gate
without replacing it (§13.2.1, §16 scenario 7(c)).

## No inbound authentication, and no admin API

The gateway does not authenticate its callers, and exposes no registry write
API. Both follow from the same boundary.

The specification defines what the federation tier *does* — how a query is
rewritten, dispatched, merged and attributed — not who may invoke it. An
authorization model is a deployment concern that varies by jurisdiction and
topology, and a reference implementation that shipped one would be asserting a
choice the specification does not make. Nor is there a useful half-measure: a
token check with no claims model, no scopes and no revocation story looks like
protection while providing little.

So the gateway is built to sit *behind* access control rather than to implement
it:

- `Authorization` is forwarded to member nodes untouched by the `passthrough`
  outbound profile, so an edge that authenticates can propagate identity through
  without this gateway parsing the token.
- Nothing about the caller is recorded, because nothing is recorded at all —
  attribution is the surrounding deployment's concern, along with the access
  control that establishes it.

**Anything that can reach this port can query the federation.** That is the
operative deployment constraint.

An `/admin/**` write surface would need the authorization story this build
deliberately does not have, so the member registry is deployment configuration
applied from a document at startup. The specification's only defined
introspection surface is the N30 self-description, and that is what this
implements. The stored-query registry is the one write API the gateway does
expose, because ITS-REST defines it and §12.7 federates it; it holds AQL text,
never membership, and it is as open as every other route. The decisions this
leaves to a deployment are written out in [security.md](security.md) (§13.4).

## Persistence

Two Flyway migrations, `V1__federation.sql` and `V2__stored_query.sql`:

| Table | Holds |
|---|---|
| `organisation`, `node`, `endpoint` | The member registry |
| `node_identifier` | External identifiers a localizer uses for a node |
| `system_id_mapping` | `creating_system_id` → node, registered and learned |
| `ehr_node_index` | Which node owns which `ehr_id` |
| `resolution_binding` | Cached subject resolutions, hashed |
| `integrity_incident` | `ehr_id` collisions (N42) |
| `stored_query` | Named, immutably versioned AQL definitions (§12.7) |

No clinical content and no patient identifier is stored in any of them.
