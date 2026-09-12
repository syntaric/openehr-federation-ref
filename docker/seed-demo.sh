#!/bin/sh
# Seeds the demo federation with clinical content, then drives a minute of
# federated AQL traffic against the gateway so the console has something real
# to show.
#
# Three phases, in order:
#
#   1. EHRs      — one per demo patient per node, so the static identity
#                  mapping in application-docker.yml resolves (both patients
#                  exist on BOTH nodes; that is what makes federation visible).
#   2. Content   — the IPS operational template plus one composition per
#                  patient per node, from docker/demo-data/.
#   3. Load      — ~80 AQL queries over ~20s through POST /v1/query/aql,
#                  drawn from a weighted mix of realistic request shapes.
#                  The console's 14-day history comes from the separate
#                  `seed-telemetry` backfill; this burst supplies the real
#                  traffic at the newest edge of the window.
#
# Idempotent: an existing EHR (409) and an already-uploaded template (409)
# both count as success. Compositions are NOT idempotent — re-running adds
# another version of each, which is harmless for a demo but means row counts
# grow. Retries while the nodes are still starting up.
set -u

EHRBASE="${EHRBASE_URL:-http://ehrbase:8080/ehrbase/rest/openehr}"
FERROEHR="${FERROEHR_URL:-http://ferroehr:8080/ferroehr/rest/openehr}"
GATEWAY="${FEDERATION_URL:-http://federation:8080}"
DATA="${DEMO_DATA_DIR:-/demo-data}"
TEMPLATE_ID="International Patient Summary"

# Phase 3 knobs — OFF by default.
#
# This phase exists to generate traffic, and it was worth its ~20s only when
# there was an operator console with charts to fill. There is no console here,
# so the default is 0 and `docker compose up` is that much faster. Set
# DEMO_LOAD_TOTAL to a positive number to drive load anyway (useful when
# profiling the fan-out, or when pointing a dashboard of your own at the stack).
LOAD_TOTAL="${DEMO_LOAD_TOTAL:-0}"
LOAD_BURST="${DEMO_LOAD_BURST:-8}"
LOAD_SECONDS="${DEMO_LOAD_SECONDS:-20}"

P1=12345
P2=67890
P1_EHRBASE=11111111-1111-4111-8111-111111111111
P1_FERROEHR=22222222-2222-4222-8222-222222222222
P2_EHRBASE=33333333-3333-4333-8333-333333333333
P2_FERROEHR=44444444-4444-4444-8444-444444444444

# ---------------------------------------------------------------------------
# phase 1 — EHRs
# ---------------------------------------------------------------------------

put_ehr() {
    url="$1"
    attempts=0
    while [ "$attempts" -lt 90 ]; do
        code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$url" \
            -H 'Accept: application/json' \
            -H 'Content-Type: application/json' \
            -H 'Prefer: return=minimal') || code=000
        case "$code" in
            200|201|204|409)
                echo "seeded $url (HTTP $code)"
                return 0
                ;;
        esac
        attempts=$((attempts + 1))
        sleep 2
    done
    echo "FAILED to seed $url (last HTTP $code)" >&2
    return 1
}

# ---------------------------------------------------------------------------
# phase 2 — template + compositions
# ---------------------------------------------------------------------------

# EHRbase answers 406 unless the client will accept XML back, even though the
# response body is empty; FerroEHR does not care. Sending both content types
# satisfies each without a per-node special case.
put_template() {
    base="$1"
    attempts=0
    while [ "$attempts" -lt 60 ]; do
        code=$(curl -s -o /tmp/tpl.out -w '%{http_code}' -X POST \
            "$base/v1/definition/template/adl1.4" \
            -H 'Content-Type: application/xml' \
            -H 'Accept: application/xml' \
            --data-binary @"$DATA/$TEMPLATE_ID.opt") || code=000
        case "$code" in
            200|201|204|409)
                echo "template '$TEMPLATE_ID' -> $base (HTTP $code)"
                return 0
                ;;
        esac
        attempts=$((attempts + 1))
        sleep 2
    done
    echo "FAILED to upload template to $base (last HTTP $code)" >&2
    cat /tmp/tpl.out >&2 2>/dev/null
    return 1
}

# Canonical JSON, not the simplified/flat format: it is the only composition
# representation BOTH demo CDRs accept on the standard openEHR endpoint.
# EHRbase 2.35 has no flat endpoint at all, and FerroEHR's flat parser is
# stricter than the fixtures these compositions came from.
post_composition() {
    base="$1"; ehr="$2"; file="$3"
    attempts=0
    while [ "$attempts" -lt 60 ]; do
        code=$(curl -s -o /tmp/comp.out -w '%{http_code}' -X POST \
            "$base/v1/ehr/$ehr/composition" \
            -H 'Content-Type: application/json' \
            -H 'Accept: application/json' \
            -H 'Prefer: return=minimal' \
            --data-binary @"$DATA/$file") || code=000
        case "$code" in
            200|201|204)
                echo "composition $file -> $ehr (HTTP $code)"
                return 0
                ;;
        esac
        attempts=$((attempts + 1))
        sleep 2
    done
    echo "FAILED to post $file to $base/$ehr (last HTTP $code)" >&2
    cat /tmp/comp.out >&2 2>/dev/null
    return 1
}

# ---------------------------------------------------------------------------
# phase 3 — representative AQL load
# ---------------------------------------------------------------------------
#
# The mix below is weighted the way a real deployment's traffic is: mostly
# cheap, narrow, patient-scoped lookups (a chart opening, a summary panel
# refreshing), with a thinner tail of heavier and more exotic queries. Every
# query carries the canonical subject predicate the gateway resolves on
# (spec §7.1) unless it is deliberately exercising a federation directive.
#
# Query bank, indexed 0..19. Slots repeat on purpose — repetition IS the
# weighting, and it keeps selection to a single modulo with no float maths.
#
# ORDER BY is written as a path ("ORDER BY c/context/start_time/value"), never
# as a SELECT alias — the AQL parser rejects an alias there with "unknown FROM
# alias", which is a parser rule, not a federation limitation.

aql_for() {
    case "$1" in
        # --- chart open: which documents exist for this patient? (x4) -------
        0|5|10|15)
            printf 'SELECT c/uid/value AS composition_id, c/name/value AS document, c/context/start_time/value AS recorded FROM EHR e CONTAINS COMPOSITION c WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"' ORDER BY c/context/start_time/value DESC LIMIT 20' "$2"
            ;;
        # --- problem list panel (x3) ----------------------------------------
        1|6|11)
            printf 'SELECT d/data[at0001]/items[at0002]/value/value AS diagnosis, d/data[at0001]/items[at0077]/value/value AS onset FROM EHR e CONTAINS COMPOSITION c CONTAINS EVALUATION d[openEHR-EHR-EVALUATION.problem_diagnosis.v1] WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"'' "$2"
            ;;
        # --- latest vitals: blood pressure (x3) ------------------------------
        2|7|12)
            printf 'SELECT o/data[at0001]/events[at0006]/data[at0003]/items[at0004]/value/magnitude AS systolic, o/data[at0001]/events[at0006]/data[at0003]/items[at0005]/value/magnitude AS diastolic, o/data[at0001]/events[at0006]/time/value AS measured FROM EHR e CONTAINS COMPOSITION c CONTAINS OBSERVATION o[openEHR-EHR-OBSERVATION.blood_pressure.v2] WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"' ORDER BY o/data[at0001]/events[at0006]/time/value DESC LIMIT 5' "$2"
            ;;
        # --- allergy banner — safety check before prescribing (x2) -----------
        3|13)
            printf 'SELECT a/data[at0001]/items[at0002]/value/value AS substance, a/data[at0001]/items[at0101]/value/value AS criticality FROM EHR e CONTAINS COMPOSITION c CONTAINS EVALUATION a[openEHR-EHR-EVALUATION.adverse_reaction_risk.v2] WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"'' "$2"
            ;;
        # --- identity resolution probe: where does this patient exist? (x2) --
        4|14)
            printf 'SELECT e/ehr_id/value AS ehr_id FROM EHR e WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"'' "$2"
            ;;
        # --- provenance: which endpoint served each row? (§8.3) -------------
        8)
            printf 'SELECT p/id AS endpoint_id, p/organisation AS organisation, c/uid/value AS composition_id FROM ENDPOINT p [ "node_ehrbase", "node_ferroehr" ] CONTAINS EHR e CONTAINS COMPOSITION c WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"'' "$2"
            ;;
        # --- count, scoped to one node --------------------------------------
        # An aggregate cannot fan out — COUNT over 2 nodes would be summed
        # wrongly, so the gateway rejects it (FED_AGGREGATE_UNSUPPORTED) and
        # tells the caller to pin a node. This is what a client does about it.
        9)
            printf 'SELECT COUNT(c/uid/value) AS document_count FROM ENDPOINT [ "node_ehrbase" ] CONTAINS EHR e CONTAINS COMPOSITION c WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"'' "$2"
            ;;
        # --- pulse trend ----------------------------------------------------
        16)
            printf 'SELECT o/data[at0002]/events[at0003]/data[at0001]/items[at0004]/value/magnitude AS pulse_bpm, o/data[at0002]/events[at0003]/time/value AS measured FROM EHR e CONTAINS COMPOSITION c CONTAINS OBSERVATION o[openEHR-EHR-OBSERVATION.pulse.v2] WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"' ORDER BY o/data[at0002]/events[at0003]/time/value DESC' "$2"
            ;;
        # --- single-node scope: only the hospital's record (§8.1) -----------
        17)
            printf 'SELECT c/uid/value AS composition_id, c/context/start_time/value AS recorded FROM ENDPOINT [ "node_ehrbase" ] CONTAINS EHR e CONTAINS COMPOSITION c WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"'' "$2"
            ;;
        # --- composer audit: who wrote into this chart? ----------------------
        18)
            printf 'SELECT c/composer/name AS composer, c/context/start_time/value AS recorded FROM EHR e CONTAINS COMPOSITION c WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"' ORDER BY c/context/start_time/value DESC' "$2"
            ;;
        # --- body weight ----------------------------------------------------
        *)
            printf 'SELECT o/data[at0002]/events[at0003]/data[at0001]/items[at0004]/value/magnitude AS weight_kg FROM EHR e CONTAINS COMPOSITION c CONTAINS OBSERVATION o[openEHR-EHR-OBSERVATION.body_weight.v2] WHERE e/ehr_status/subject/external_ref/id/value = '"'"'%s'"'"'' "$2"
            ;;
    esac
}

# JSON-escape: the AQL strings contain double quotes (directive endpoint lists)
# and must survive being embedded in a JSON request body.
json_escape() {
    sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

run_load() {
    if [ "$LOAD_TOTAL" -le 0 ]; then
        echo "load: skipped (DEMO_LOAD_TOTAL=$LOAD_TOTAL)"
        return 0
    fi
    echo "load: ~$LOAD_TOTAL queries over ~${LOAD_SECONDS}s against $GATEWAY/v1/query/aql"

    # Wait for the gateway to be able to answer at all before starting the
    # clock, otherwise the first seconds are spent on connection refusals and
    # the run is neither 500 queries nor representative.
    attempts=0
    while [ "$attempts" -lt 90 ]; do
        code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/v1/query/aql" \
            -H 'Content-Type: application/json' \
            -d '{"q":"SELECT e/ehr_id/value AS ehr_id FROM EHR e WHERE e/ehr_status/subject/external_ref/id/value = '"'"'12345'"'"'"}') || code=000
        [ "$code" = "200" ] && break
        attempts=$((attempts + 1))
        sleep 2
    done
    if [ "$code" != "200" ]; then
        echo "FAILED: gateway did not answer AQL before the load phase (last HTTP $code)" >&2
        return 1
    fi

    bursts=$((LOAD_TOTAL / LOAD_BURST))
    [ "$bursts" -lt 1 ] && bursts=1
    # Integer centiseconds, so a 60s/63-burst run still paces correctly under
    # a shell with no floating point. `sleep` in busybox/coreutils takes a
    # decimal, so this is printed back as seconds with 2 decimals.
    gap_cs=$((LOAD_SECONDS * 100 / bursts))

    sent=0
    ok=0
    failed=0
    i=0
    start=$(date +%s)

    burst=0
    while [ "$burst" -lt "$bursts" ]; do
        # One burst = LOAD_BURST concurrent requests, which is what makes the
        # per-node latency percentiles in the console meaningful — serial
        # requests would never show queueing.
        n=0
        while [ "$n" -lt "$LOAD_BURST" ]; do
            # Alternate patients; 12345 gets the heavier share, as the
            # patient with data on both nodes usually would.
            case $((i % 3)) in
                0) patient=$P2 ;;
                *) patient=$P1 ;;
            esac
            q=$(aql_for $((i % 20)) "$patient" | json_escape)
            (
                code=$(curl -s -o /dev/null -w '%{http_code}' -m 20 \
                    -X POST "$GATEWAY/v1/query/aql" \
                    -H 'Content-Type: application/json' \
                    -H "X-Request-Id: demo-load-$i" \
                    -d "{\"q\":\"$q\"}") || code=000
                [ "$code" = "200" ] || echo "$code" >> /tmp/load-fail
            ) &
            i=$((i + 1))
            n=$((n + 1))
            sent=$((sent + 1))
        done
        wait
        burst=$((burst + 1))

        # Progress every ~10 bursts so the compose log shows movement.
        if [ $((burst % 10)) -eq 0 ]; then
            echo "  load: $sent/$LOAD_TOTAL sent ($(($(date +%s) - start))s elapsed)"
        fi

        sleep "$(printf '%d.%02d' $((gap_cs / 100)) $((gap_cs % 100)))"
    done

    elapsed=$(($(date +%s) - start))
    if [ -f /tmp/load-fail ]; then
        failed=$(wc -l < /tmp/load-fail | tr -d ' ')
        rm -f /tmp/load-fail
    fi
    ok=$((sent - failed))
    echo "load: $sent queries in ${elapsed}s — $ok ok, $failed failed"

    # A handful of failures under concurrency is not a seeding failure; a
    # wholesale failure is. 10% is the line.
    if [ "$failed" -gt $((sent / 10)) ]; then
        echo "FAILED: $failed/$sent load queries did not return 200" >&2
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------

rc=0

put_ehr "$EHRBASE/v1/ehr/$P1_EHRBASE"  || rc=1
put_ehr "$FERROEHR/v1/ehr/$P1_FERROEHR" || rc=1
put_ehr "$EHRBASE/v1/ehr/$P2_EHRBASE"  || rc=1
put_ehr "$FERROEHR/v1/ehr/$P2_FERROEHR" || rc=1

if [ "$rc" -eq 0 ]; then
    put_template "$EHRBASE"  || rc=1
    put_template "$FERROEHR" || rc=1
fi

if [ "$rc" -eq 0 ]; then
    post_composition "$EHRBASE"  "$P1_EHRBASE"  composition-12345-hospital.json || rc=1
    post_composition "$FERROEHR" "$P1_FERROEHR" composition-12345-clinic.json   || rc=1
    post_composition "$EHRBASE"  "$P2_EHRBASE"  composition-67890-hospital.json || rc=1
    post_composition "$FERROEHR" "$P2_FERROEHR" composition-67890-clinic.json   || rc=1
fi

if [ "$rc" -eq 0 ]; then
    run_load || rc=1
fi

exit "$rc"
