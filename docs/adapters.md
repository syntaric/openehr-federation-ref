# Writing a regional adapter

## Why this is an extension point

The normative body of the specification (§1–§18) defines localization,
identifier cross-reference, addressing, authentication and consent as **abstract
roles**. It names IHE profiles only as *proposed bindings*.

Every concrete national service appears only in the **informative Annex B**, and
§2.4 is explicit that a different region may supply a different Annex B without
touching §1–§18.

So a reference implementation that shipped one country's adapter would present a
regional choice as though it were part of the specification. What belongs here
instead is the seam — and two stubs that demonstrate it:

- `identity/impl/nvi/NviLocalizationService` — a plain **record locator**
- `identity/impl/mitz/MitzLocalizationService` — a **consent-aware** locator

Both carry the implementation guide in their Javadoc. They are documentation
that compiles, and they are the best starting point for a real adapter.

**A deployment with no adapter at all is fully conformant.** `mode: none`
selects the specification's own ask-all fallback (§4.3 variant B), and N27a is
explicit that a deployment with no consent service conforms.

## The interface

```java
public interface PatientLocalizationService {
    LocalizationResult localize(PatientToken token, List<EndpointDescriptor> members);
}
```

Exactly one implementation is active, selected by
`federation.localization.mode`. They are alternatives rather than a chain: a
federation has one localization authority, and consulting two would produce a
union neither of them sanctioned.

## Implementing one

### 1. Wire it up

The mode enum, the `@ConditionalOnProperty` value and the class must agree —
`LocalizationModeWiringTest` pins them together, because Spring matches the
property *text* and never consults the enum, so the two can otherwise drift
silently into a context with no localization bean at all.

```java
@Component
@ConditionalOnProperty(name = "federation.localization.mode", havingValue = "myregion")
public class MyRegionLocalizationService implements PatientLocalizationService {
```

Add the constant to `FederationProperties.Localization.Mode`. Only
`AllNodesLocalizationService` may carry `matchIfMissing = true`.

### 2. Call your service, inside its own budget

`federation.localization.timeout` (default 3s) is deliberately separate from the
fan-out budget: this call happens inside the clinical request, and without its
own bound a slow national service silently eats the allowance, after which
member nodes start reporting `time-out` for a reason that has nothing to do with
them.

### 3. Map identifiers through the registry, not through code

Regional services identify organisations in their own namespace — a
care-provider number, an OID, a community id — not by this gateway's endpoint
ids. `node_identifier` rows exist for exactly this:

```java
List<Node> owners = registry.nodesForIdentifier("ura", "00000001");
```

Registry rows, because this is deployment data that changes when a member joins
or leaves. Hard-coding the correspondence means a code change per membership
change, which is how a federation's registry drifts out of date.

An identifier naming no known node is routine — a national index answers about
the whole country, and most of it is not in your federation. Count it and carry
on; do not fail the query.

### 4. Return the right result

This is the part where a wrong choice is a clinical-safety bug rather than a
style question.

| Factory | Use when |
|---|---|
| `localized(endpointIds, categories)` | A **plain record locator**: you know *where* records are. Almost always this one. |
| `localized(endpointIds, consentDeniedEndpointIds, categories)` | A **consent-aware** service that genuinely adjudicates release |
| `noRecords()` | The service answered, and holds nothing for this patient |
| `unavailable(reason)` | The service could not be reached, or answered with something you cannot trust |
| `disabled()` | Reserved for the ask-all fallback |

**The second parameter is not a general "excluded nodes" list.**

- A node **absent from `endpointIds`** holds no records here. A *where*-answer.
- A node in **`consentDeniedEndpointIds`** was **refused**. A *release*-answer,
  reported to the caller as `consent-denied` under N27a.

Putting a merely-absent node in the second set asserts a consent decision that
no consent authority ever made (§14.4) — it reports a refusal that never
happened. The inverse error loses a real refusal, and the caller can no longer
distinguish "nobody there" from "you were told no".

Presence in `endpointIds` is candidacy, never clearance: the node still applies
N27 and may refuse on its own. Your adapter must not suppress that refusal
because it had cleared the node.

### 5. Never throw

A backend failure is `unavailable(...)`, so the pipeline can apply the
configured `on-failure` policy. An exception thrown from `localize` takes down a
query the operator may have chosen to let through.

Under the default `on-failure: closed`, an `UNAVAILABLE` result yields no
candidates and the query returns no records. That is the correct degradation: an
adapter that cannot answer must **not** fall back to ask-all, because ask-all
lets every member node learn that someone asked about this patient — precisely
the property the adapter exists to provide, discarded at the moment the operator
believed they had switched it on.

`ask-all` is available as an explicit, audited deployment choice. It is never
what a failure silently becomes.

### 6. Mind the cache

`federation.localization.cache-ttl` (default 15m) is a **correctness bound**,
not a performance knob. Consent can be revoked at any moment, so it is the
window during which the gateway may act on a permission that no longer holds.
Lengthening it is a clinical-governance decision.

## Testing

`LocalizationPolicyIT` drives the gateway with a caller-controlled
`LocalizationResult` and asserts the gateway-side behaviour every adapter
inherits — only localized nodes are queried, excluded nodes are never contacted,
a localized node may still refuse. Those properties are already covered.

What your adapter needs to test is its own half: that your service's answers map
to the right `LocalizationResult`, and — most importantly — that a "holds no
records" answer does not become a `consent-denied`.

## Contributing one

We are unlikely to merge a working adapter for one particular country: it would
present a regional choice as part of the specification, and it would need
credentials and a live national endpoint that CI cannot have.

Very welcome, though: improvements to the SPI, to the stubs' guidance, or to
this document. If you have built a real adapter, we would be glad to link to
your repository.
