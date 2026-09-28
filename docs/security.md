# Security decisions (§13.4, CP-39)

Spec §13.4 does not prescribe an authentication solution. It fixes five
questions a deployment is not allowed to leave unanswered, and CP-39 scores
whether the answers are *documented* — "verified as documentation at admission,
not per request", because there is no wire artefact to assert.

This repository is a build, not a deployment. Its answer to most items is that
the surrounding deployment decides, and it says *which* decisions it delegates
and what it does to stay usable underneath them. That is still an answer: the
failure §13.4 guards against is the deployment that "never notices it chose".
A deployment of this gateway inherits this page as its starting point and
replaces each delegated answer with its own.

The one design decision that shapes every answer below is stated in
[configuration.md](configuration.md#inbound-authentication): **this gateway
performs no inbound authentication and sits behind the deployment's access
control.** Nothing here softens that.

## Which identity is verified across the trust boundary

**Outbound, gateway → node:** the gateway authenticates as an *organisation or
service*, never as a clinician, exactly as §13.1 models it. Per endpoint the
registry names one of four profiles — `passthrough`, `static:<key>`,
`oauth2-jwt` (RFC 7523 private-key JWT client assertion) or `oauth2-secret`
(RFC 6749 client credentials) — and under the two token-acquiring profiles the
identity asserted at the node's boundary is the gateway's `client_id` at that
node's authorization server. The node's authorization server, and whatever
register it consults, is the authentic source the identity is checked against;
this gateway makes no claim about which register that is.

**Inbound, client → gateway:** nothing is verified here. Whose identity is
asserted at the gateway's boundary, by whom, and against which authentic source
of organisation identity, is the deployment's decision and is enforced at its
edge (an API gateway, a service mesh, a reverse proxy enforcing OAuth2, or
network isolation). "The systems trust each other" does not satisfy §13.4;
naming the register the edge checks does, and it is the deployment that names it.

## Who authenticates the end user, and where that trust stops

The requesting organisation authenticates its own clinicians, in its own
domain, under its own obligations. This gateway never re-authenticates an end
user and never sees one: it has no user model, no session and no claims
parser. A member node does not re-authenticate the end user either, and MAY
rely on the requester's assertion that it did — that is the federated trust
model §13.4 describes, and the division the exchange rests on.

What the gateway does is keep that assertion intact on its way through. Under
the `passthrough` profile the caller's `Authorization` header reaches the
member node unchanged, so an edge that authenticates the clinician can
propagate that identity end to end without this gateway parsing it. Under a
token-acquiring profile the caller's token is *replaced* by the gateway's own
client credential, and the end-user assertion travels only if the deployment
carries it some other way (a regional profile per Annex B, or a claim the
edge injects). Which of these applies is a per-endpoint registry choice and a
deployment must make it knowingly.

## Purpose of use

The gateway does not define, infer or convey purpose of use. A query says what
is being asked for and never what for, and §13.4 forbids relying on a node to
infer it from the query — so purpose of use must travel *beside* the request,
as a token claim, a header, or a regional mechanism such as the Dutch
Generic-Functions authorization grant (Annex B).

Whatever carries it, this gateway lets it through: request headers other than
hop-by-hop ones are forwarded on routed `/v1/ehr/**` requests, and the
`Authorization` header is forwarded on fan-out under `passthrough`. A
deployment MUST choose the carrier and MUST verify, per outbound profile, that
it survives the hop — under a token-acquiring profile a purpose claim inside
the *caller's* token does not, because that token is not what the node
receives. Purpose is the input to N26's delegated decision and N27's consent
gate at the node; a deployment that leaves it out has left the node unable to
decide.

## What the token is bound to

Two decisions, taken separately as §13.4 requires.

**Bearer or sender-constrained.** Every token this gateway acquires or forwards
is a plain bearer token. The gateway does not implement DPoP (RFC 9449) or
mutual-TLS-bound tokens (RFC 8705), and a token it forwards under
`passthrough` is forwarded as received. A deployment that crosses
organisational trust boundaries SHOULD consider whether bearer-token replay is
acceptable in its setting; if it is not, sender-constraining is added at the
edge and at the nodes, and the `passthrough` profile relays the constrained
token without understanding it. The gateway itself cannot present a DPoP proof
for a token it did not mint.

**Transport identity is not application-layer identity.** TLS to a member
node authenticates the *host*; the organisation the gateway acts as is
asserted at the application layer by the outbound profile (the `client_id` in
the RFC 7523 assertion or the client-credentials grant). The gateway does not
treat a TLS peer certificate as an organisation identity, and does not treat a
node's TLS identity as its openEHR `system_id` — `system_id` comes from the
registry and is verified against what the node stamps into version UIDs. A
deployment that uses mutual TLS as its organisation identity mechanism makes
that decision at its edge and records it here.

## What the technique does not cover

Some risk is carried outside the transaction, and the split for this build is:

**Addressed technically, in this gateway:**

- A member node is located by its own `ehr_id` and never receives the patient
  identifier, in the AQL, the URL, the query string or the headers (N33; the
  hygiene gate refuses dispatch otherwise).
- No clinical content and no patient identifier is persisted; `resolution_binding`
  holds an HMAC of the patient reference. A stored-query definition that
  carries a literal patient identifier is refused before it is stored.
- Outbound credentials are encrypted at rest under `registry-secret-key`, and
  the gateway refuses to start with credentials it cannot decrypt.
- Consent is never inferred from a localization hit; a node's refusal is
  reported, never overridden (N27, N27a).

**Addressed by agreement, outside this gateway:**

- **Admission.** Who may join the federation, on what evidence, and under
  which identifier-integrity conditions (§12b, N42a; CP-33a is deferred here
  for exactly this reason). The registry is applied from a document the
  operator controls.
- **Inbound access control.** Who may query the federation at all, and who
  may store a named query. Every route on this gateway — including
  `PUT /v1/definition/query/**` — answers whoever reaches it.
- **Audit and supervision.** §2.2 puts audit outside the specification.
  This gateway records nothing about a request; attribution, retention and
  the ability to trace a request back to the person or system that made it
  belong to the surrounding deployment and to each member's governance.
- **Logging and liability.** After the fact, the surrounding norms decide
  who is answerable for a request. The gateway's contribution is that the
  acting endpoint travels on every response (`openEHR-federation-endpoint`,
  `meta.federation.endpoints[]`), so a request *can* be traced to the node
  that answered it.

A deployment replaces the second list with its own agreements and keeps the
first as the floor this build guarantees.
