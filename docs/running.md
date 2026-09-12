# Running

## Requirements

- JDK 21
- PostgreSQL 16
- Docker, for the demo stack and the integration tests

## The demo stack

The fastest way to see a federation actually federate:

```bash
docker compose up --build
```

That brings up six services: the gateway and its Postgres, plus two member CDR
nodes — EHRbase and FerroEHR — each with its own database. A one-shot
`seed-demo` service then creates the demo EHRs and loads one composition per
patient per node.

Two demo patients have records on **both** nodes, which is the whole point: a
single query returns merged rows from two different CDR products.

> The gateway performs no inbound authentication — see
> [configuration.md](configuration.md#inbound-authentication). The demo also
> ships a published encryption key and CDRs with auth disabled. It is a
> demonstration, never a deployment base.

### Check that the registry landed

The gateway applies `docker/demo-registry.json` at startup:

```bash
docker compose logs federation | grep 'registry bootstrap'
# registry bootstrap: 6 created, 0 updated from file:/app/demo-registry.json
```

If that line is missing, the gateway did not start — the bootstrap fails the
boot rather than starting with an empty federation.

### Try it

```bash
BASE=http://localhost:8080

# Federation self-description (N30) — no credentials needed, by design.
curl -s -X OPTIONS $BASE/v1/ | jq

# Federated AQL across both nodes. `meta.endpoints` carries one entry per node,
# each with its own latency (N40).
curl -s -X POST $BASE/v1/query/aql \
  -H 'Content-Type: application/json' \
  -d '{"q":"SELECT c/uid/value FROM EHR e CONTAINS COMPOSITION c"}' \
  | jq '.meta.endpoints'

# A subject-scoped query. The gateway rewrites the subject predicate into each
# node's own ehr_id before dispatch — no node sees the patient identifier.
curl -s -X POST $BASE/v1/query/aql \
  -H 'Content-Type: application/json' \
  -d '{"q":"SELECT c/uid/value AS uid FROM EHR e CONTAINS COMPOSITION c
            WHERE e/ehr_status/subject/external_ref/id/value = '"'"'12345'"'"'"}' \
  | jq '.rows, .meta.endpoints'

# Take an ehr_id from those results and request it directly: follow-up routing
# lands on the node that owns it (N22/N23/N36/N41).
curl -s $BASE/v1/ehr/<ehr_id> | jq
```

Tear down with `docker compose down -v`.

## Running the gateway alone

```bash
mvn clean package
java -jar target/openehr-federation-ref-*.jar
```

It needs a database and a registry. See [configuration.md](configuration.md).

## The registry document

There is no registry write API — see [architecture.md](architecture.md#no-inbound-authentication-and-no-admin-api)
for why — so the registry is supplied as a document and applied at startup:

```yaml
federation:
  registry:
    bootstrap-file: file:/etc/federation/registry.json
```

```json
{
  "organisations": [
    { "id": "demo-hospital", "name": "Demo Hospital" }
  ],
  "nodes": [
    {
      "nodeId": "node_a",
      "systemId": "cdr-a.example.org",
      "organisationId": "demo-hospital",
      "product": "EHRbase",
      "version": "2.35.1"
    }
  ],
  "endpoints": [
    {
      "endpointId": "node_a",
      "nodeId": "node_a",
      "baseUrl": "http://cdr-a.example.org/ehrbase/rest/openehr",
      "authProfile": "oauth2-secret",
      "oauthClientId": "federation-gateway",
      "oauthTokenEndpoint": "https://as.example.org/token",
      "status": "active"
    }
  ],
  "identifiers": [
    { "nodeId": "node_a", "system": "ura", "value": "00000001" }
  ]
}
```

Notes:

- **`systemId`** is the node's `creating_system_id`. It is what follow-up
  routing resolves against, so it must match what the CDR actually stamps into
  the version UIDs it mints.
- **`status`** is `active`, `offline` or `excluded`. Only `active` endpoints are
  queried; `excluded` is an operator's explicit decision to take a node out of
  fan-out without discarding its history. This is *membership and health* — the
  federation's standing configuration — and is a different vocabulary from the
  per-query statuses in `meta.endpoints[]` (§7a.2, §11.1). `active` here does not
  predict `active` there, and a per-query value such as `not-localized` is
  meaningless in this field.
- **Secrets are never in the document.** A client secret or signing key is
  supplied out of band and stored encrypted; re-applying a document that omits
  them keeps what is stored rather than clearing it.
- **`identifiers`** map a node to the identifiers a regional localization
  service uses for it — see [adapters.md](adapters.md).

Applying is idempotent, so the same document can be re-applied on every boot. It
never deletes: a document that omits a node leaves that node alone.

## Building and testing

```bash
mvn clean verify        # everything, including Testcontainers ITs (needs Docker)
mvn clean test          # unit tests only, no Docker
mvn -B clean verify -DskipITs   # the fast loop

# The conformance matrix prints a full CP-1..CP-37 report.
mvn test -Dtest=ConformanceMatrixTest -Dsurefire.useFile=false
```

