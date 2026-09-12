# Architecture

The gateway is one Spring Boot application over one Postgres database. It holds
no clinical data: every record it returns was fetched from a member node during
the request that returned it.

## Packages

All under `com.syntaric.federation`:

| Package | Responsibility |
|---|---|
| `api` | HTTP surface: `/v1/query/aql`, `/v1/ehr/**`, `OPTIONS {base}/`, error rendering |
| `query` | The federation engine — see below |
| `query.aql` | Parse, analyse, rewrite and merge AQL |
| `query.fanout` | Parallel dispatch and the timeout budget |
| `query.routing` | Follow-up routing: which node owns this `ehr_id` or version UID |
| `identity` | Resolving a subject to per-node `ehr_id`s, and the localization SPI |
| `registry` | Members: organisations, nodes, endpoints, identifiers |
| `outbound` | HTTP clients to member nodes, and outbound auth profiles |
| `security` | Outbound credential encryption and its boot-time validation |
| `config` | Properties and startup wiring |

## Enforced boundaries

Five rules in `ArchitectureTest` hold the shape. They exist because each one
guards a mistake that reads as a reasonable simplification at the point someone
makes it.

| Rule | Forbids |
|---|---|
| `identitySpiDependsOnNothingInternal` | The SPI reaching into internals, so it stays extractable |
| `nothingDependsOnControllers` | Depending back on the HTTP layer |
| `registryStaysALeaf` | `registry` reaching into query, outbound or identity |
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
the response in `meta.endpoints` (N40) and are not persisted; request tracking
and telemetry are deployment concerns the specification does not define, and a
fork that wants them adds them where it wants the cost.

## Request flow

### Federated AQL — `POST /v1/query/aql`

1. **Parse and analyse.** Reject what cannot be answered correctly across a
   federation rather than answering it wrongly: `OFFSET > 0` (N39, §11.6.2),
   aggregates that are not decomposable, a subject predicate under `OR`.
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
7. **Merge.** Reconcile columns across nodes, apply `ORDER BY` and `LIMIT`
   across the merged set, and attach provenance.

The response is 200 with `meta.endpoints` — one entry per node, each with a
status and a latency (N40). A partial federation is a partial *result*, not a
failed request.

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
  receives its own `ehr_id` and never the patient identifier.
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
deliberately does not have, so the registry is deployment configuration applied
from a document at startup. The specification's only defined introspection
surface is the N30 self-description, and that is what this implements.

## Persistence

One Flyway migration, `V1__federation.sql`:

| Table | Holds |
|---|---|
| `organisation`, `node`, `endpoint` | The member registry |
| `node_identifier` | External identifiers a localizer uses for a node |
| `system_id_mapping` | `creating_system_id` → node, registered and learned |
| `ehr_node_index` | Which node owns which `ehr_id` |
| `resolution_binding` | Cached subject resolutions, hashed |
| `integrity_incident` | `ehr_id` collisions (N42) |

No clinical content is stored in any of them.
