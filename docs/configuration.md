# Configuration

Everything lives under the `federation.*` prefix and binds from
`application.yml`, environment variables, or any other Spring property source.
Environment variables use the usual relaxed binding:
`federation.registry.bootstrap-file` is `FEDERATION_REGISTRY_BOOTSTRAP_FILE`.

## Federation identity

Echoed verbatim in the `OPTIONS {base}/` self-description (N30), which is how a
client discovers what it is talking to.

| Property | Default | Meaning |
|---|---|---|
| `federation.federation.id` | `reference-federation` | Stable identifier for this federation |
| `federation.federation.name` | `openEHR Federation Reference Gateway` | Human-readable name |
| `federation.federation.organisation` | — | Operating organisation |
| `federation.federation.product` | `openehr-federation-ref` | Product identifier |
| `federation.federation.version` | `0.1.0` | Deployed version |
| `federation.federation.jwks-uri` | — | Where this gateway's JWKS is served, reported as `federation.auth.jwks_uri` (§13.1) |

`jwks-uri` is how a node discovers the keys to verify this gateway's RFC 7523
client assertions against. §13.1 requires the location be *discoverable*, not
that the gateway host it — point this at wherever your PKI already serves the key
set. Left unset, the `auth` block is omitted from the self-description entirely,
which is honest but leaves a node unable to verify without an out-of-band
arrangement.

## Timeouts

Both the per-node timeout and the overall budget are mandatory under N38 and are
declared in the self-description, so a client can know what patience it is
buying before it asks.

| Property | Default | Meaning |
|---|---|---|
| `federation.timeouts.per-node` | `10s` | How long any single node may take |
| `federation.timeouts.overall-budget` | `15s` | Ceiling for the whole fan-out |
| `federation.timeouts.connect` | `2s` | TCP connect timeout per node |

A node that misses its slice is reported `time-out` in `meta.endpoints` and the
query still answers 200 with whatever else arrived. Exhausting the overall
budget before enough nodes answer yields `FED_INCOMPLETE` (504).

Set `overall-budget` with the fan-out in mind: it is a wall-clock ceiling across
nodes queried in parallel, not a sum, but localization (below) draws from the
same request.

## Inbound authentication

**There is none, and there is nothing to configure.** This gateway does not
authenticate its callers. Every route it serves — `/v1/query/aql`, `/v1/ehr/**`,
`OPTIONS {base}/` — answers whoever asks.

> **Anything that can reach this port can query the federation.** Do not expose
> it directly. Put it behind whatever your deployment's access control policy
> requires: an API gateway, a service mesh, a reverse proxy enforcing OAuth2, or
> network isolation.

This is a scope decision rather than an omission. The specification defines the
federation tier's *behaviour* — how a query is rewritten, dispatched, merged and
attributed — not who is entitled to issue one. Authorization models differ by
jurisdiction, by organisation and by deployment topology, and a reference
implementation that shipped one would be asserting a choice the specification
does not make. There is also no half-measure available: a token check with no
claims model, no scopes and no revocation story would look like protection while
providing very little.

What the gateway does instead is stay usable underneath one:

- **`Authorization` is forwarded, not consumed.** The `passthrough` outbound
  profile relays the caller's header to member nodes unchanged, so a deployment
  that authenticates at its edge can propagate that identity all the way through
  without this gateway needing to understand the token.
- **Nothing about the caller is recorded.** There is no audit trail here to
  attribute to — §2.2 puts audit outside the specification and leaves it to each
  participant's governance — so identity is the surrounding deployment's concern
  end to end.

Note that `OPTIONS {base}/` is *intended* to be reachable without credentials
even in a protected deployment: the self-description (N30) is what a client
reads before it has any, and it carries membership information rather than
patient data.

## Outbound credentials

How the gateway authenticates *to* each member node. The profile is chosen per
endpoint in the registry; this sets the fallback for endpoints that name none.

| Property | Default | Meaning |
|---|---|---|
| `federation.security.default-outbound-profile` | `passthrough` | Profile for endpoints that declare none |
| `federation.security.static-tokens` | — | Map of key → token, for the `static:<key>` profile |
| `federation.security.registry-secret-key` | — | Encrypts stored credentials |

Profiles:

| Profile | Behaviour |
|---|---|
| `passthrough` | Forward the caller's own token |
| `static:<key>` | A fixed token from `static-tokens` |
| `oauth2-jwt` | OAuth2 client credentials with an RFC 7523 private-key JWT client assertion |
| `oauth2-secret` | OAuth2 client credentials with an RFC 6749 §2.3.1 shared secret |

Both token-acquiring profiles are OAuth2 `client_credentials`; the name says how
the *client* authenticates, which is the only axis on which they differ.

### `registry-secret-key`

Encrypts the outbound credentials stored on each endpoint — the signing key and
the client secret. A base64 AES key (16/24/32 bytes) or a passphrase, which is
folded to 256 bits.

> **Losing this key makes every stored credential unrecoverable.** There is no
> escrow and, by design, no plaintext copy anywhere. The gateway refuses to
> start if the registry holds credentials it cannot read, rather than letting
> that surface one unauthorized query at a time.

Unset by default: a deployment using only `passthrough` and `static` needs no
key, and shipping a default would mean a published key protecting real
credentials.

## Registry bootstrap

| Property | Default | Meaning |
|---|---|---|
| `federation.registry.bootstrap-file` | — | A registry document to apply at startup |

A Spring resource, so `file:/etc/federation/registry.json` and
`classpath:registry.json` both work. Unset is a silent no-op.

Applying it is idempotent: upsert per id, nothing is ever deleted, and rows
absent from the document are left alone. A missing file, malformed JSON or a
dangling reference **fails the boot** — which is safe precisely *because* the
import is idempotent, so the remedy is always "fix the file and restart".

Starting anyway would be the worse outcome: a gateway with an empty federation
answers queries, returns 200, and reports zero rows, which is indistinguishable
from a patient genuinely having no records.

See [running.md](running.md) for the document format.

## Patient identity

| Property | Default | Meaning |
|---|---|---|
| `federation.identity.mode` | `static` | `static` or `pixm` |
| `federation.identity.static-mappings` | — | `<patientId>.<nodeId>` → `ehr_id`, for `mode: static` |
| `federation.identity.ref-hash-secret` | `change-me-in-deployment` | Keys the hash written to `resolution_binding` |
| `federation.identity.binding-ttl` | `24h` | How long a resolution may be reused |

`resolution_binding` stores only an HMAC of the patient reference, never the raw
identifier (N33). **Change `ref-hash-secret` in any real deployment** — the
default is a placeholder, and a shared default would make the stored hashes
comparable across deployments.

## Localization

Which nodes are candidates for an undirected query (N4, §14).

| Property | Default | Meaning |
|---|---|---|
| `federation.localization.mode` | `none` | `none`, `nvi`, or `mitz` |
| `federation.localization.on-failure` | `closed` | `closed` or `ask-all` |
| `federation.localization.timeout` | `3s` | Budget for the localization call |
| `federation.localization.cache-ttl` | `15m` | How long an answer may be reused |

`none` is the specification's own ask-all fallback (§4.3 variant B) and is fully
conformant. The other modes select regional Annex B adapters, which this build
ships as documented stubs — see [adapters.md](adapters.md).

**`on-failure: closed`** means an unreachable localizer yields no candidates.
The alternative, `ask-all`, trades the privacy property for availability: it
lets every member node learn that someone asked about this patient, which is
precisely what localization exists to prevent. It is a deliberate, audited
deployment choice, never a silent degradation.

**`timeout`** is separate from the fan-out budget on purpose. The localization
call happens inside the clinical request, so without its own bound a slow
national service silently eats the allowance and member nodes start reporting
`time-out` for a reason that has nothing to do with them.

**`cache-ttl`** is a correctness bound, not a performance knob: consent can be
revoked at any moment, so this is the window in which the gateway may act on a
permission that no longer holds.

## Database

Ordinary Spring Data JDBC properties; Flyway runs the schema at startup.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/federation
    username: federation
    password: federation
```

## Profiles

| Profile | Purpose |
|---|---|
| *(none)* | The packaged default: no bootstrap file, no demo data |
| `dev` | Local development against mock CDRs |
| `docker` | The compose demo stack |

`dev` and `docker` differ only in where they point the database and the member
nodes. Neither weakens anything the default profile enforces, because inbound
authentication is not something this build performs in any profile.
