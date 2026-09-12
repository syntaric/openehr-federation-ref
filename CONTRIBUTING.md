# Contributing

Thanks for your interest. This repository is a *reference implementation*, which
shapes what a good contribution looks like here: the specification is the
authority, and this code exists to demonstrate it faithfully.

## Cite the specification

Discussion here is grounded in *Proposal for Federation Tier with AQL* v0.3
(Antora component `federation-aql`, CC0). Please cite it precisely:

- **§ number** for a section — "§13.2.1 says localization filters in front of
  the consent gate".
- **N-number** for a numbered normative statement — "N27a permits a deployment
  with no consent service".
- **CP-number** for a conformance point from §17 — "this is CP-19".

A claim about what the gateway *should* do is much easier to act on with a
citation than without one. If the specification is silent or ambiguous, say so —
that is a useful finding, and it belongs in the specification repository rather
than being settled by implementation here.

## A change to federation behaviour moves the conformance matrix

`ConformanceMatrixTest` mirrors spec §17 one-to-one. It is the repository's
claim about itself, and it is the first thing a reader checks.

So: **a pull request that changes federation behaviour must move the matrix.**
In practice that means one of

- a new or changed test carrying the relevant `@Tag("CP-n")`,
- a change to a deferral, with its stated reason updated, or
- an explicit note in the PR that coverage is unchanged and why.

A behaviour change that leaves the matrix untouched is the one shape of PR that
will reliably be sent back, because it means the repository is now asserting
something it no longer tests.

Deferred conformance points each carry a reason in `DEFERRED`. All current
deferrals are node-side, operator or deployment obligations that a gateway test
suite cannot perform. If you can turn one into a real test, that is a very
welcome contribution.

## Regional adapters

Concrete national services (record locators, consent authorities) live in the
specification's **informative Annex B**, and §2.4 provides for a region
supplying a different Annex B without touching the normative §1–§18.

This repository therefore ships the *seam*, not the adapters: the
`PatientLocalizationService` SPI plus two documented stubs. We are unlikely to
merge a working adapter for one particular country — it would present a regional
choice as part of the specification, and it would need credentials and a live
national endpoint that CI cannot have.

What is very welcome: improvements to the SPI, to the stubs' guidance, or to
`docs/adapters.md`. If you have built a real adapter, we would be glad to link
to your repository.

## Tests

The project targets meaningful coverage of behaviour, not a percentage.

- **Unit tests** (`mvn test`) need no Docker.
- **Integration tests** (`mvn verify`) use Testcontainers and need a working
  Docker daemon. They share one Postgres, so a test that writes registry or
  `resolution_binding` rows must clean up after itself — a cached localization
  denial leaking between classes has caused a real, hard-to-find flake before.
- **Architecture tests** enforce the package boundaries. Note that most are
  `noClasses().that()` rules, which pass vacuously on an empty match: if you
  change a package name, check that the rule still *bites* by temporarily
  introducing the violation it forbids.

Follow the surrounding style: JUnit 5 with AssertJ, `@DisplayName` where the
method name alone does not carry the intent, and a comment explaining *why* a
property matters when that is not obvious.

## Commits and pull requests

- [Conventional Commits](https://www.conventionalcommits.org/): `fix(query):`,
  `feat(registry):`, `docs:`, `test:`.
- Keep the subject under about 50 characters and explain the reasoning in the
  body. The *why* is what survives.

## Versioning

The version tracks the **specification** version, not a product release: `0.3.x`
implements spec v0.3. A patch bump is an implementation change; the minor
version moves when the specification does.

## Reporting security issues

**Please do not open a public issue.** Report privately through
[GitHub security advisories](https://github.com/syntaric/openehr-federation-ref/security/advisories/new).

The same applies to anything involving **real patient data** — an identifier in
a log, a query returning records it should not have. Treat it as an incident,
not a bug report, and do not attach logs, AQL or response payloads from a live
system to any public issue: redacting them by hand is unreliable, since an
`ehr_id` is as identifying as a name once it can be joined back to a record.
Describe the shape of the problem instead.

Note that the absence of inbound authentication is documented and deliberate
(see [docs/configuration.md](docs/configuration.md#inbound-authentication)) — it
is the design, not a defect. A way to *bypass* access control a deployment has
placed in front is very much worth reporting.
