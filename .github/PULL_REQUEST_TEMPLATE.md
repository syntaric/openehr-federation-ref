## What this changes

<!-- One or two sentences. The *why* matters more than the *what*. -->

## Specification basis

<!--
Cite what this rests on: § number, N-number, CP-number. If the change does not
touch federation behaviour (docs, build, test tooling), say "n/a".
-->

## Conformance matrix

<!--
A change to federation behaviour must move `ConformanceMatrixTest`. Tick one:
-->

- [ ] Adds or changes a `@Tag("CP-n")` test
- [ ] Changes a deferral and its stated reason
- [ ] Coverage is unchanged, because: <!-- explain -->
- [ ] n/a — this does not change federation behaviour

## Checklist

- [ ] `mvn clean verify` passes
- [ ] If a package or class moved: ArchUnit rules still *bite* (they pass
      vacuously on an empty match — check by temporarily introducing the
      violation the rule forbids)
- [ ] No real patient data, credentials or absolute local paths in the diff
