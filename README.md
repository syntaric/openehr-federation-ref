# openEHR Federation Tier — reference implementation

[![CI](https://github.com/syntaric/openehr-federation-ref/actions/workflows/ci.yml/badge.svg)](https://github.com/syntaric/openehr-federation-ref/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A working implementation of *Proposal for Federation Tier with AQL* v0.4.0 — one
AQL endpoint in front of many openEHR CDRs.

A client sends one query. The gateway works out which nodes could hold records
for the patient, rewrites the query into each node's own `ehr_id`, dispatches in
parallel, merges the results, and answers with per-node provenance attached. The
client never learns the nodes' internal identifiers, and no node learns the
patient identifier.

## What this is

The specification's reference implementation. It exists to show that the
specification is implementable, and to be precise about *how* — which is why the
conformance matrix mirrors spec §17 one-to-one and fails the build when coverage
regresses.

- **AQL fan-out** with subject→`ehr_id` rewriting, an identifier hygiene gate,
  result merge, and `meta.endpoints` provenance per node (N40)
- **Byte-identical `/v1/ehr/**` proxy** with follow-up routing on `ehr_id` and
  `creating_system_id` (N22/N23/N36/N41), and integrity incidents on an `ehr_id`
  collision (N42)
- **`OPTIONS {base}/` self-description** (N30)
- **Outbound OAuth2** — RFC 7523 private-key JWT client assertion, or RFC 6749
  client secret — configured per endpoint, credentials encrypted at rest
- **Localization SPI** (N4/§14) with the conformant ask-all fallback and two
  documented stubs for regional bindings

## What this is not

This is a reference implementation: complete and conformant with respect to
§1–§18 of the specification, and deliberately minimal with respect to everything
else. It ships no operator console, no telemetry store, no health monitoring,
and no regional Annex B adapters. Those are deployment concerns the
specification does not define, and this repository does not have opinions about
them.

Three consequences worth stating up front:

- **It does not authenticate its callers.** There is no inbound auth of any
  kind, in any profile. The specification defines the federation tier's
  behaviour, not who may invoke it, and authorization models differ by
  jurisdiction and deployment. **Anything that can reach this port can query the
  federation — put it behind your own access control.** The gateway is built to
  sit there: it forwards `Authorization` to member nodes untouched.
- **The registry has no write API.** It is applied from a document at startup.
  An `/admin/**` surface would need its own authorization story, which is the
  same decision as above.
- **There is no consent service, and none is required.** N27a is explicit that a
  deployment without one is fully conformant — consent is enforced at each
  member node under N27, which is where the specification puts that obligation.

## Specification conformance

Conformant with respect to **§1–§18**, the normative body of *Proposal for
Federation Tier with AQL* v0.4.0 (Antora component `federation-aql`, CC0).

The claim is machine-checked: `ConformanceMatrixTest` requires every point in
CP-1..CP-37 to be covered by a tagged test or explicitly deferred with a reason.
All four deferrals are node-side, operator or deployment obligations that a
gateway test suite cannot verify.

```bash
mvn test -Dtest=ConformanceMatrixTest -Dsurefire.useFile=false
```

Full matrix and the error vocabulary: [docs/conformance.md](docs/conformance.md).

## Quick start

```bash
docker compose up --build
```

Six services: the gateway, its Postgres, and two member CDR nodes — EHRbase and
FerroEHR — each with its own database. Two demo patients have records on *both*
nodes, so a single query returns merged rows from two different CDR products.

```bash
BASE=http://localhost:8080

# Self-description — no credentials needed, by design (N30).
curl -s -X OPTIONS $BASE/v1/ | jq

# One query, two CDR products, merged.
curl -s -X POST $BASE/v1/query/aql \
  -H 'Content-Type: application/json' \
  -d '{"q":"SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c"}' \
  | jq '.rows, .meta.endpoints'
```

> The demo stack ships a published encryption key and member CDRs with their own
> authentication disabled. It is a demonstration, never a deployment base.

More: [docs/running.md](docs/running.md).

## Configuration

Everything is under `federation.*`. The essentials:

| Property | Default | Meaning |
|---|---|---|
| `federation.registry.bootstrap-file` | — | Registry document applied at startup |
| `federation.security.registry-secret-key` | — | Encrypts stored outbound credentials |
| `federation.timeouts.overall-budget` | `15s` | Ceiling for the whole fan-out (N38) |
| `federation.localization.mode` | `none` | Ask-all fallback, or a regional adapter |

Full reference: [docs/configuration.md](docs/configuration.md).

## Architecture

One Spring Boot application over one Postgres. It holds no clinical data: every
record it returns was fetched from a member node during the request that
returned it.

The boundaries that matter are enforced by ArchUnit rather than described in
prose — in particular **Invariant 0**: a federated query does no synchronous
database work. Nothing on that path records anything, because audit is
explicitly out of scope in the specification (§2.2).

[docs/architecture.md](docs/architecture.md).

## Extending: regional adapters

Concrete national services — record locators, consent authorities — live in the
specification's *informative* Annex B, and §2.4 provides for a region supplying
a different Annex B without touching §1–§18.

So this repository ships the seam rather than the adapters: the
`PatientLocalizationService` SPI, plus two stubs that document how to implement
one. The stubs are the best starting point.

[docs/adapters.md](docs/adapters.md).

## Contributing

Contributions are welcome. Two things shape them here: cite the specification by
§, N-number or CP-number, and **a change to federation behaviour must move the
conformance matrix**.

See [CONTRIBUTING.md](CONTRIBUTING.md).

**Security issues and anything involving real patient data go through
[private disclosure](https://github.com/syntaric/openehr-federation-ref/security/advisories/new),
never a public issue.** This gateway sits in front of clinical record
repositories: do not attach logs, AQL or response bodies from a live system to
anything public.

## License

[Apache-2.0](LICENSE). The specification itself is published separately under
CC0.

## Commercial support

This repository is maintained by [Syntaric](https://syntaric.com), which also
develops **Unio**, a commercial openEHR federation product built on the same
core.

The distinction is deployment surface, not conformance: the table below is
identical for every row the specification defines. This build is conformant on
its own — N27a for consent, §2.4 for regional bindings — so it is a reference
implementation, not a trial version.

| | This repository | Unio |
|---|---|---|
| Spec conformance (§1–§18, CP-1..CP-37) | Full | Full |
| AQL fan-out, rewrite, merge | ✓ | ✓ |
| `/v1/ehr/**` byte-identical proxy | ✓ | ✓ |
| Follow-up + `ehr_id` routing, integrity incidents | ✓ | ✓ |
| `OPTIONS {base}/` self-description | ✓ | ✓ |
| Outbound OAuth2 / RFC 7523 JWT client assertion | ✓ | ✓ |
| Registry model | file bootstrap | ✓ + admin API/UI |
| Localization SPI | ✓ (interface + stubs) | ✓ |
| Operator console (web UI) | — | ✓ |
| Inbound authentication / RBAC | — (deploy behind your own) | ✓ |
| Request tracking + per-node telemetry | — | ✓ |
| Traffic / latency / error analytics | — | ✓ |
| Endpoint health probing + auto-offline | — | ✓ |
| Audit trail + retention | — (§2.2: out of scope) | ✓ |
| NVI adapter (Annex B, NL) | stub + guide | ✓ |
| Mitz adapter (Annex B, NL) | stub + guide | ✓ |
| Support, SLA, deployment assistance | community, best-effort | ✓ |

Audit is **deliberately absent**, not merely unimplemented: §2.2 states the
specification "does not specify an audit event model, what must be recorded,
retention, or a federation-wide audit trail", and leaves audit to each
participant's own governance. A reference implementation that shipped one would
be inventing a model the specification declines to define.

Enquiries: <hello@syntaric.com>.
