// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.conformance;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-enforcing traceability matrix against the federation spec's three ID
 * schemes: conformance points (CP-1..CP-37 +CP-33a, §17), normative
 * requirements (N1..N43 +N27a +N42a, §6) and Connectathon tracks
 * (TRACK-1..11, §16.3). Tracked against spec 0.3.1.
 *
 * <p>Coverage is discovered by scanning every test class under
 * {@code com.syntaric.federation} for {@code @Tag}s — <em>not</em> from a hand-listed
 * whitelist. A whitelist silently under-reports: the list this replaced named
 * 15 classes while 22 further CP-tagged classes existed, so the "no invented
 * CP tags" assertion below could not see the tags it was meant to police.
 *
 * <p>N-coverage is <em>derived</em>, not tagged separately. §17 already maps
 * each CP to the requirements it discharges; re-asserting that mapping with a
 * second set of hand-applied {@code @Tag("N-n")} annotations would create a
 * copy that can drift from the spec. Instead {@link #CP_REQUIREMENTS} mirrors
 * the spec's own table, and an N counts as covered when any CP naming it is
 * covered. Requirements no CP names at all are spec-side traceability gaps and
 * are listed in {@link #UNTRACEABLE_N} with the spec's own § reference.
 */
class ConformanceMatrixTest {

    /** Deferred CPs and why (all are node-side / operator / deployment obligations). */
    private static final Map<String, String> DEFERRED = Map.of(
            "CP-18", "Delegated access decisions are a node-side obligation, untestable at the gateway",
            "CP-20", "Registry connectionType codes are seeded data; conformance is a deployment property",
            "CP-27", "Node-side obligation: invocable on ehr_id alone; verified at admission, not here",
            "CP-33a", "Operator admission conditions are organisational, not gateway behaviour");

    /**
     * The spec's §17 CP → requirements mapping, transcribed. Keep in step with
     * {@code modules/ROOT/pages/conformance.adoc}; {@link #everyRequirementIsTraceable()}
     * checks this covers the whole N-space so a transcription slip cannot pass
     * silently.
     */
    private static final Map<String, List<String>> CP_REQUIREMENTS = Map.ofEntries(
            Map.entry("CP-1", List.of("N1")),
            Map.entry("CP-2", List.of("N2", "N5")),
            Map.entry("CP-3", List.of("N3")),
            Map.entry("CP-4", List.of("N7")),
            Map.entry("CP-5", List.of("N4", "N10")),
            Map.entry("CP-6", List.of("N11")),
            Map.entry("CP-7", List.of("N5")),
            Map.entry("CP-8", List.of("N13")),
            Map.entry("CP-9", List.of("N15")),
            Map.entry("CP-10", List.of("N14")),
            Map.entry("CP-11", List.of("N16")),
            Map.entry("CP-12", List.of("N6", "N16")),
            Map.entry("CP-13", List.of("N19", "N20", "N21")),
            Map.entry("CP-14", List.of("N22")),
            Map.entry("CP-15", List.of("N23")),
            Map.entry("CP-16", List.of("N24")),
            Map.entry("CP-17", List.of("N25")),
            Map.entry("CP-18", List.of("N26")),
            Map.entry("CP-19", List.of("N27", "N27a")),
            Map.entry("CP-20", List.of("N19")),
            Map.entry("CP-21", List.of("N28")),
            Map.entry("CP-22", List.of("N29")),
            Map.entry("CP-23", List.of("N30")),
            Map.entry("CP-24", List.of("N31")),
            Map.entry("CP-25", List.of("N32")),
            Map.entry("CP-26", List.of("N33")),
            Map.entry("CP-27", List.of("N34")),
            Map.entry("CP-28", List.of("N35")),
            Map.entry("CP-29", List.of("N36")),
            Map.entry("CP-30", List.of("N37")),
            Map.entry("CP-31", List.of("N38", "N40")),
            // N9 added to this CP in 0.3.1: per-node LIMIT/OFFSET/ORDER BY is
            // not a correct federated answer without the N39 merge rules.
            Map.entry("CP-32", List.of("N39", "N14", "N9")),
            Map.entry("CP-33", List.of("N41", "N42")),
            Map.entry("CP-33a", List.of("N42a")),
            Map.entry("CP-34", List.of("N43")),
            // Added in spec 0.3.1, closing the traceability gaps this
            // implementation reported: N17/N18 (CP-35), N8 (CP-36), N12
            // (CP-37) previously reached no conformance point.
            Map.entry("CP-35", List.of("N17", "N18")),
            Map.entry("CP-36", List.of("N8")),
            Map.entry("CP-37", List.of("N12")));

    /**
     * Requirements no conformance point names, with the spec location that
     * would have to change. These are gaps in the specification's own
     * traceability, not in this implementation: a requirement reachable from no
     * CP cannot be scored at a Connectathon whatever an implementation does.
     *
     * <p><b>Empty as of spec 0.3.1.</b> All five former entries — N8, N9, N12,
     * N17, N18 — now reach a conformance point: CP-35 (N17, N18), CP-36 (N8),
     * CP-37 (N12), and N9 added to CP-32. The map is kept rather than deleted
     * because {@link #everyRequirementIsTraceable()} is what would catch a
     * future requirement added without one.
     */
    private static final Map<String, String> UNTRACEABLE_N = Map.of();

    /**
     * Connectathon tracks (§16.3) and the tests that execute them. A track is
     * covered when a test carries {@code @Tag("TRACK-n")}.
     */
    private static final Map<String, String> DEFERRED_TRACK = Map.of(
            "TRACK-7", "Auth conveyance is exercised, but configurations (b) and (c) need a consent "
                    + "service. This build implements none — which N27a states is fully conformant, "
                    + "consent being a node-side obligation under N27. JWKS publication is left "
                    + "unspecified by the spec.",
            "TRACK-8", "PMIR merge/split propagation; the spec itself marks it deferrable "
                    + "(§16.3 'hooks reserved', §18)",
            "TRACK-11", "Gateway half is covered; the node-admission half (§12b.2) is an operator "
                    + "obligation verified at admission, not at the gateway");

    private static final Pattern SPEC_TAG = Pattern.compile("^(CP-[0-9]+[a-z]?|TRACK-[0-9]+)$");

    // ---------------------------------------------------------------- CPs

    @Test
    void everyConformancePointIsCoveredOrExplicitlyDeferred() {
        Set<String> all = allConformancePoints();
        Set<String> covered = coveredTags("CP-");

        // sanity: no invented CP tags. With classpath scanning this now sees
        // every tagged class, so plan-milestone numbers cannot hide here.
        assertThat(covered).allSatisfy(cp -> assertThat(all).contains(cp));
        // a deferred CP must not silently gain a tag (stale deferral)
        assertThat(covered).doesNotContainAnyElementsOf(DEFERRED.keySet());

        Set<String> missing = new TreeSet<>(all);
        missing.removeAll(covered);
        missing.removeAll(DEFERRED.keySet());
        assertThat(missing)
                .withFailMessage("Conformance points neither tested nor deferred: %s", missing)
                .isEmpty();
    }

    // ---------------------------------------------------------------- requirements

    /**
     * The transcribed §17 mapping must span the whole requirement space: every
     * N is either named by some CP or listed as untraceable. A requirement that
     * is neither means the transcription has drifted from the spec.
     */
    @Test
    void everyRequirementIsTraceable() {
        Set<String> namedByCp = new TreeSet<>();
        CP_REQUIREMENTS.values().forEach(namedByCp::addAll);

        Set<String> unaccounted = new TreeSet<>(allRequirements());
        unaccounted.removeAll(namedByCp);
        unaccounted.removeAll(UNTRACEABLE_N.keySet());
        assertThat(unaccounted)
                .withFailMessage("Requirements neither named by a CP nor recorded as untraceable: %s "
                        + "(CP_REQUIREMENTS has drifted from spec §17)", unaccounted)
                .isEmpty();

        // and nothing may be claimed untraceable while a CP in fact names it
        Set<String> bothWays = new TreeSet<>(UNTRACEABLE_N.keySet());
        bothWays.retainAll(namedByCp);
        assertThat(bothWays)
                .withFailMessage("Listed as untraceable but named by a CP: %s", bothWays)
                .isEmpty();
    }

    /**
     * Every requirement reachable from a CP must be discharged by a covered or
     * explicitly deferred CP — the N-level analogue of the CP matrix.
     */
    @Test
    void everyTraceableRequirementIsCoveredOrDeferred() {
        Set<String> covered = coveredTags("CP-");
        Set<String> uncovered = new TreeSet<>();

        for (String n : allRequirements()) {
            if (UNTRACEABLE_N.containsKey(n)) {
                continue;
            }
            boolean discharged = CP_REQUIREMENTS.entrySet().stream()
                    .filter(e -> e.getValue().contains(n))
                    .anyMatch(e -> covered.contains(e.getKey()) || DEFERRED.containsKey(e.getKey()));
            if (!discharged) {
                uncovered.add(n);
            }
        }
        assertThat(uncovered)
                .withFailMessage("Requirements whose every CP is missing: %s", uncovered)
                .isEmpty();
    }

    // ---------------------------------------------------------------- tracks

    @Test
    void everyTestTrackIsCoveredOrExplicitlyDeferred() {
        Set<String> all = allTracks();
        Set<String> covered = coveredTags("TRACK-");

        assertThat(covered).allSatisfy(t -> assertThat(all).contains(t));
        assertThat(covered).doesNotContainAnyElementsOf(DEFERRED_TRACK.keySet());

        Set<String> missing = new TreeSet<>(all);
        missing.removeAll(covered);
        missing.removeAll(DEFERRED_TRACK.keySet());
        assertThat(missing)
                .withFailMessage("Connectathon tracks neither tested nor deferred: %s", missing)
                .isEmpty();
    }

    // ---------------------------------------------------------------- report

    /**
     * Single source of truth for the conformance claims in {@code README.md}
     * and {@code documentation/overview.rst}; those numbers are generated from
     * this output rather than hand-maintained.
     */
    @Test
    void matrixReport() {
        Set<String> coveredCps = coveredTags("CP-");
        Set<String> coveredTracks = coveredTags("TRACK-");

        Map<String, String> cps = new LinkedHashMap<>();
        for (String cp : allConformancePoints()) {
            cps.put(cp, coveredCps.contains(cp) ? "covered"
                    : DEFERRED.containsKey(cp) ? "deferred: " + DEFERRED.get(cp)
                    : "MISSING");
        }
        System.out.printf("%n=== Conformance points (spec §17) — %d covered, %d deferred ===%n",
                count(cps, "covered"), DEFERRED.size());
        cps.forEach((cp, state) -> System.out.printf("%-7s %s%n", cp, state));

        Map<String, String> ns = new LinkedHashMap<>();
        for (String n : allRequirements()) {
            if (UNTRACEABLE_N.containsKey(n)) {
                ns.put(n, "untraceable (spec): " + UNTRACEABLE_N.get(n));
                continue;
            }
            List<String> viaCovered = CP_REQUIREMENTS.entrySet().stream()
                    .filter(e -> e.getValue().contains(n) && coveredCps.contains(e.getKey()))
                    .map(Map.Entry::getKey).toList();
            List<String> viaDeferred = CP_REQUIREMENTS.entrySet().stream()
                    .filter(e -> e.getValue().contains(n) && DEFERRED.containsKey(e.getKey()))
                    .map(Map.Entry::getKey).toList();
            ns.put(n, !viaCovered.isEmpty() ? "covered via " + String.join(",", viaCovered)
                    : !viaDeferred.isEmpty() ? "deferred via " + String.join(",", viaDeferred)
                    : "MISSING");
        }
        System.out.printf("%n=== Requirements (spec §6) — %d covered, %d untraceable in spec ===%n",
                (int) ns.values().stream().filter(v -> v.startsWith("covered")).count(),
                UNTRACEABLE_N.size());
        ns.forEach((n, state) -> System.out.printf("%-7s %s%n", n, state));

        Map<String, String> tracks = new LinkedHashMap<>();
        for (String t : allTracks()) {
            tracks.put(t, coveredTracks.contains(t) ? "covered"
                    : DEFERRED_TRACK.containsKey(t) ? "deferred: " + DEFERRED_TRACK.get(t)
                    : "MISSING");
        }
        System.out.printf("%n=== Test tracks (spec §16.3) — %d covered, %d deferred ===%n",
                count(tracks, "covered"), DEFERRED_TRACK.size());
        tracks.forEach((t, state) -> System.out.printf("%-9s %s%n", t, state));

        assertThat(cps).doesNotContainValue("MISSING");
        assertThat(ns).doesNotContainValue("MISSING");
        assertThat(tracks).doesNotContainValue("MISSING");
    }

    private static int count(Map<String, String> m, String value) {
        return (int) m.values().stream().filter(value::equals).count();
    }

    // ---------------------------------------------------------------- denominators

    private Set<String> allConformancePoints() {
        Set<String> all = new LinkedHashSet<>();
        // CP-35..CP-37 added in spec 0.3.1.
        for (int i = 1; i <= 37; i++) {
            all.add("CP-" + i);
            if (i == 33) {
                all.add("CP-33a");
            }
        }
        return all;
    }

    private Set<String> allRequirements() {
        Set<String> all = new LinkedHashSet<>();
        for (int i = 1; i <= 43; i++) {
            all.add("N" + i);
            if (i == 27) {
                all.add("N27a");
            }
            if (i == 42) {
                all.add("N42a");
            }
        }
        return all;
    }

    private Set<String> allTracks() {
        Set<String> all = new LinkedHashSet<>();
        for (int i = 1; i <= 11; i++) {
            all.add("TRACK-" + i);
        }
        return all;
    }

    // ---------------------------------------------------------------- scanning

    /**
     * Every spec tag on every test class under {@code com.syntaric.federation}, class-
     * and method-level, excluding this class (which names every CP in its own
     * maps and would otherwise score the whole matrix as covered).
     */
    private Set<String> coveredTags(String prefix) {
        Set<String> covered = new TreeSet<>();
        for (Class<?> testClass : testClasses()) {
            for (Tag tag : testClass.getAnnotationsByType(Tag.class)) {
                covered.add(tag.value());
            }
            for (Method method : testClass.getDeclaredMethods()) {
                for (Tag tag : method.getAnnotationsByType(Tag.class)) {
                    covered.add(tag.value());
                }
            }
        }
        covered.removeIf(tag -> !tag.startsWith(prefix));
        return covered;
    }

    private List<Class<?>> testClasses() {
        JavaClasses scanned = new ClassFileImporter().importPackages("com.syntaric.federation");
        return scanned.stream()
                .filter(c -> !c.getFullName().equals(ConformanceMatrixTest.class.getName()))
                .filter(c -> c.getName().endsWith("Test") || c.getName().endsWith("IT"))
                .<Class<?>>map(c -> {
                    try {
                        return Class.forName(c.getFullName(), false,
                                ConformanceMatrixTest.class.getClassLoader());
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("scanned class not loadable: " + c.getFullName(), e);
                    }
                })
                .toList();
    }

    /**
     * Guards the tag namespace itself: a {@code @Tag} that looks like a spec
     * identifier must be one. Plan-milestone tags use the {@code M-n} form so
     * the CP namespace stays exclusively the spec's.
     */
    @Test
    void specTagNamespaceIsNotPollutedByPlanMilestones() {
        Set<String> invented = new TreeSet<>();
        Set<String> valid = new TreeSet<>(allConformancePoints());
        valid.addAll(allTracks());

        for (Class<?> testClass : testClasses()) {
            Set<String> tags = new TreeSet<>();
            for (Tag tag : testClass.getAnnotationsByType(Tag.class)) {
                tags.add(tag.value());
            }
            for (Method method : testClass.getDeclaredMethods()) {
                for (Tag tag : method.getAnnotationsByType(Tag.class)) {
                    tags.add(tag.value());
                }
            }
            for (String tag : tags) {
                Matcher m = SPEC_TAG.matcher(tag);
                if (m.matches() && !valid.contains(tag)) {
                    invented.add(tag + " (" + testClass.getSimpleName() + ")");
                }
            }
        }
        assertThat(invented)
                .withFailMessage("Tags in the spec namespace that are not spec identifiers: %s. "
                        + "Plan-milestone tags must use the M-n form.", invented)
                .isEmpty();
    }
}
