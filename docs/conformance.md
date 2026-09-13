# Conformance

This implementation targets *Proposal for Federation Tier with AQL* v0.9.0
(Antora component `federation-aql`), and claims conformance with respect to
**§1–§18**, the normative body of that specification.

The claim is machine-checked. `ConformanceMatrixTest` mirrors spec §17
one-to-one: every conformance point is either covered by a test carrying the
matching `@Tag("CP-n")`, or explicitly deferred here with a reason. Adding an
untagged CP, or silently dropping coverage, fails the build.

Regenerate the table below at any time:

```bash
mvn -q test -Dtest=ConformanceMatrixTest -Dsurefire.useFile=false
```

## What "conformant" means here

Two points are worth stating plainly, because both are places where a reader
might reasonably expect this build to be a cut-down version and it is not.

**No consent service is required.** N27a is explicit that a deployment with no
consent service is fully conformant — consent is enforced at each member node
under N27, which is where the specification puts that obligation regardless. The
gateway's duty is not to *usurp* it, and that is tested (CP-19).

**No regional adapter is required.** The normative body defines localization,
identifier cross-reference, addressing, auth and consent as *abstract roles*,
and names IHE profiles only as proposed bindings. Concrete national services
appear only in the informative Annex B, and §2.4 provides for a region supplying
a different Annex B without touching §1–§18. This build ships the SPI and two
documented stubs — see [adapters.md](adapters.md).

## Matrix

| Point | Status | Reason, if deferred |
|---|---|---|
| `CP-1` | Covered | |
| `CP-2` | Covered | |
| `CP-3` | Covered | |
| `CP-4` | Covered | |
| `CP-5` | Covered | |
| `CP-6` | Covered | |
| `CP-7` | Covered | |
| `CP-8` | Covered | |
| `CP-9` | Covered | |
| `CP-10` | Covered | |
| `CP-11` | Covered | |
| `CP-12` | Covered | |
| `CP-13` | Covered | |
| `CP-14` | Covered | |
| `CP-15` | Covered | |
| `CP-16` | Covered | |
| `CP-17` | Covered | |
| `CP-18` | Deferred | Delegated access decisions are a node-side obligation, untestable at the gateway |
| `CP-19` | Covered | |
| `CP-20` | Deferred | Registry connectionType codes are seeded data; conformance is a deployment property |
| `CP-21` | Covered | |
| `CP-22` | Covered | |
| `CP-23` | Covered | |
| `CP-24` | Covered | |
| `CP-25` | Covered | |
| `CP-26` | Covered | |
| `CP-27` | Deferred | Node-side obligation: invocable on ehr_id alone; verified at admission, not here |
| `CP-28` | Covered | |
| `CP-29` | Covered | |
| `CP-30` | Covered | |
| `CP-31` | Covered | |
| `CP-32` | Covered | |
| `CP-33` | Covered | |
| `CP-33a` | Deferred | Operator admission conditions are organisational, not gateway behaviour |
| `CP-34` | Covered | |
| `CP-35` | Covered | Added in 0.3.1 (N17, N18) |
| `CP-36` | Covered | Added in 0.3.1 (N8) |
| `CP-37` | Covered | Added in 0.3.1 (N12) |

CP-35, CP-36 and CP-37 were added in spec 0.3.1 after this implementation
reported that five requirements — N8, N9, N12, N17 and N18 — reached no
conformance point and so could not be scored by any implementation. N9 was added
to CP-32 at the same time. All 45 requirements now trace to a conformance point.

All four deferrals are node-side, operator, or deployment obligations. None can
be verified by a gateway test suite: a gateway cannot prove that a node honours
an obligation the node owns, and asserting otherwise would be the exact
overreach §14.4 and N27 warn against. If you can turn one into a real test, that
is a very welcome contribution.

## Error vocabulary

Federated failures answer with a stable machine-readable code. On `/v1/**` these
appear in the openEHR error body; the codes themselves are protocol vocabulary
and are never renamed casually.

| Code | HTTP | Meaning |
|---|---|---|
| `FED_TARGET_CONFLICT` | 400 | The request names targets that contradict each other |
| `FED_UNKNOWN_TARGET` | 400 | A named endpoint or organisation is not in the registry |
| `FED_AQL_INVALID` | 400 | The AQL could not be parsed |
| `FED_IDENTIFIER_UNSTRIPPABLE` | 400 | A subject identifier could not be removed from the query |
| `FED_IDENTIFIER_HYGIENE` | 400 | A patient identifier would have reached a node that must not see it |
| `FED_AGGREGATE_UNSUPPORTED` | 400 | An aggregate that cannot be computed correctly across a federation |
| `FED_OFFSET_UNSUPPORTED` | 400 | `OFFSET > 0` — rejected rather than answered wrongly (N39, §11.6.2) |
| `FED_QUERY_UNSUPPORTED` | 400 | The query shape is outside what the federation tier defines |
| `FED_NO_TARGET` | 400 | Nothing to query after target resolution |
| `FED_HEADER_INVALID` | 400 | A malformed `openEHR-federation-*` header |
| `FED_REGISTRY_INVALID` | 400 | A registry value failed validation |
| `FED_WRONG_CONTROLLING_SYSTEM` | 409 | A follow-up was directed at a node that does not own the resource |
| `FED_EHR_ID_COLLISION` | 409 | One `ehr_id` claimed by two nodes — a federation integrity violation (N42) |
| `FED_NOT_FOUND` | 404 | No such resource, or a dangling registry reference |
| `FED_INCOMPLETE` | 504 | The overall budget expired before enough nodes answered |
| `FED_NOT_IMPLEMENTED` | 501 | A defined surface this build does not implement (e.g. `/v1/demographic`, N32) |
| `FED_UPSTREAM_ERROR` | 502 | A member node failed in a way that is not attributable to the request |

## Reading the result envelope

**The envelope *is* an openEHR ITS-REST `RESULT_SET` (Release-1.1.0), not a
federation-specific structure that resembles one** (§9.1). A client that can parse
a single CDR's AQL response can parse this one — which is how N1's transparency
claim holds on the wire. In particular:

- **`rows` entries are ordered arrays, not objects.** `rows[n][i]` is the value of
  `columns[i]`; a row carries no field names of its own. Before spec 0.4.0 this
  gateway emitted objects keyed by column name, matching a spec example that
  turned out to contradict ITS-REST — the example was what was wrong.
- **`columns[]` entries carry `name` and `path` and nothing else.** ITS-REST
  defines no `type` member. `path` is always this gateway's own rendering of the
  client's AQL, never a node's (N17, CP-35).
- **The federation's `meta` additions are never `_`-prefixed.** That prefix is
  reserved to openEHR's own fields (`_href`, `_type`, `_created`, …); `complete`,
  `endpoints`, `timeout` and `dedup` extend `meta` through the
  `additionalProperties: true` ITS-REST declares on it.

Both this envelope and the `OPTIONS` body are validated against the
specification's published JSON Schemas in `SpecSchemaConformanceIT`; the schema
copies live in `src/test/resources/spec-schemas/`.

A federated query answers 200 with per-node provenance in `meta.endpoints`, even
when some nodes did not contribute. That is the point: a partial federation is a
partial *result*, not a failed request, and the caller is told exactly which
nodes are behind the rows it received.

Each entry carries a `status`, from the closed set of §11.1:

| Status | Meaning |
|---|---|
| `active` | Queried, answered |
| `offline` | Queried, not reachable |
| `time-out` | Queried, did not answer inside the budget |
| `not-resolved` | Not queried — the cross-reference service returned no local `ehr_id` for this patient at this node (§5, N6) |
| `not-localized` | Not queried — localization did not name it as a candidate. **Nothing ruled it in, and nothing ruled it out**: a where-answer, never a consent decision (§11.1, §14) |
| `excluded` | Not queried — ruled out *by a decision about this node*: a directive that did not name it, or an operator policy. Some authority acted |
| `consent-denied` | A consent-aware localizer explicitly refused this node (N27a), or the node itself refused |

There is no `error` status: a node that answered with a failure is reported
`offline` or `time-out`, with the node's own message in the entry's `error` field.

`latency_ms` follows **dispatch**, not scope: it is present for `active`,
`offline` and `time-out` — the three statuses describing an attempted request —
and omitted for the four settled before any request existed to time. A `0` there
would read as "answered instantly" (§9.5, N40).

The distinction between these is load-bearing and is not cosmetic: reporting a
node that merely holds no records as `consent-denied` asserts a decision no
consent authority ever made, and reporting it as `excluded` asserts that some
authority ruled it out when none did.

**`not-localized` and `excluded` do not clear `meta.complete`.** A node in either
state was never *in scope* — node selection never intended to ask it — so a query
is not incomplete for having skipped it (§11.1 *What "in scope" means*, N37).
This matters in the ordinary case: a nine-member federation whose localizer names
three would otherwise report `complete: false` on every undirected query, and the
flag would stop meaning anything.
