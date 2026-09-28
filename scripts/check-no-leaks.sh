#!/usr/bin/env bash
#
# Branding / provenance leak gate.
#
# This repository was extracted from a commercial product. A leak is
# unrecoverable once the repo is public — a forced push does not un-fetch a
# clone and does not un-index a crawl — so this runs locally BEFORE a commit,
# not only in CI.
#
# Each pattern below has its own allowlist, because the legitimate exceptions
# differ per pattern. A blanket exclusion would hide a real leak in a file that
# is only allowed to mention one of these words.

set -uo pipefail
cd "$(dirname "$0")/.."

STATUS=0

# Search tracked files only: build output and local scratch are not shipped.
files() {
    git ls-files -z 2>/dev/null || find . -path ./.git -prune -o -type f -print0
}

# check <label> <extended-regex> [allowed-path-regex]
check() {
    local label="$1" pattern="$2" allow="${3:-}"
    local hits

    # -H because xargs may hand grep a single file, and grep then omits the
    # filename — which would report a leak without saying where it is.
    hits=$(files \
        | xargs -0 grep -IHinE -- "$pattern" 2>/dev/null \
        | { [ -n "$allow" ] && grep -vE "^($allow):" || cat; })

    if [ -n "$hits" ]; then
        echo "LEAK [$label]:"
        echo "$hits" | sed 's/^/  /'
        echo
        STATUS=1
    fi
}

# The product name. README.md may name it once, in the commercial section.
# Word-bounded: "union" and "unions" are ordinary English and appear in the
# merge code, so an unanchored match would be noise nobody reads.
check "product name" '\bunio\b|\bunio[-_.]' 'README\.md|scripts/check-no-leaks\.sh'

# An unrelated private product that leaked through the old docs toolchain.
check "unrelated product" 'openfhir' 'scripts/check-no-leaks\.sh'

# The company name. Legitimate in the package root (com.syntaric.federation),
# in the Maven coordinates, and in the ownership/licence boilerplate.
check "company name" 'syntaric' \
    'README\.md|LICENSE|NOTICE|pom\.xml|CONTRIBUTING\.md|CHANGELOG\.md|docs/.*|src/.*|scripts/check-no-leaks\.sh|\.github/.*'

# The operator console's IdP. Nothing here references it — this build performs
# no inbound authentication at all — so the pattern currently matches nothing.
# Kept anyway: it is the tripwire for a merge that drags the operator identity
# stack back in, which is precisely the shape of change that should not land
# quietly.
check "keycloak" 'keycloak' 'scripts/check-no-leaks\.sh'

# Regional Annex B services: named only in the two teaching stubs and the docs
# that mirror them.
# The two stub implementations, the Mode enum that selects them, and the tests
# and docs that name those modes. The mode names are configuration vocabulary a
# deployment types, so they cannot be anonymised — what must not appear is a
# working regional implementation, which these paths do not contain.
check "regional adapters" '\b(nvi|mitz)\b' \
    'src/main/java/com/syntaric/federation/identity/impl/(nvi|mitz)/.*|src/main/java/com/syntaric/federation/config/FederationProperties\.java|src/test/.*|docs/.*|README\.md|CHANGELOG\.md|scripts/check-no-leaks\.sh'

# Absolute paths from a developer machine.
check "local paths" '(/home/|/Users/)' 'scripts/check-no-leaks\.sh'

if [ "$STATUS" -eq 0 ]; then
    echo "check-no-leaks: clean"
fi
exit "$STATUS"
