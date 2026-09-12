// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Package-by-domain with enforced boundaries, so a later multi-module / SPI
 * extraction stays mechanical.
 *
 * <p><b>Patterns here are absolute, not {@code ..}-relative.</b> The root package
 * is {@code com.syntaric.federation}, so a relative pattern like
 * {@code ..federation..} matches every class in the build rather than the query
 * package it was meant to name. Worse, most of these are
 * {@code noClasses().that()} rules, which pass <i>vacuously</i> when the
 * {@code that()} clause matches nothing — so a pattern that silently stops
 * matching does not fail, it just stops enforcing. Absolute package names cannot
 * drift that way.
 *
 * <p>{@code archRule.failOnEmptyShould} is left at its default (enabled) for the
 * same reason: an empty rule is reported as a failure instead of a pass.
 */
@AnalyzeClasses(packages = "com.syntaric.federation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.syntaric.federation.";

    /**
     * SPI packages must stay extractable: no dependency on internals beyond the
     * query package's own vocabulary types ({@code EndpointDescriptor},
     * {@code OutboundCredentials}), which are the currency the SPI trades in.
     */
    @ArchTest
    static final ArchRule identitySpiDependsOnNothingInternal =
            classes().that().resideInAPackage(ROOT + "identity.spi..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage(ROOT + "identity.spi..", ROOT + "query", "java..", "javax..");

    /** Controllers are the top layer; nothing depends back on them. */
    @ArchTest
    static final ArchRule nothingDependsOnControllers =
            noClasses().that().resideOutsideOfPackage(ROOT + "api..")
                    .should().dependOnClassesThat().resideInAPackage(ROOT + "api");

    /**
     * The registry is a leaf domain: no reach into the query pipeline, the
     * outbound clients, or identity.
     *
     * <p>This is what keeps the registry importable from startup wiring without
     * a cycle — {@code RegistryBootstrapLoader} lives in {@code config} and
     * depends inward on {@code registry}, which only works while {@code registry}
     * depends on nothing above it.
     */
    @ArchTest
    static final ArchRule registryStaysALeaf =
            noClasses().that().resideInAPackage(ROOT + "registry..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(ROOT + "query..", ROOT + "outbound..", ROOT + "identity..");

    /** The AQL pipeline never talks to the database or HTTP clients directly. */
    @ArchTest
    static final ArchRule aqlPipelineIsPure =
            noClasses().that().resideInAPackage(ROOT + "query.aql..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(ROOT + "registry..", ROOT + "outbound..", ROOT + "identity..");

    /**
     * <b>Invariant 0, made structural.</b> The fan-out is the hottest path here
     * and must reach no persistence at all.
     *
     * <p>This is the regression guard for a real breach: {@code sampleLatency}
     * once called {@code RegistryService.recordLatency()} on the dispatch thread,
     * doing an INSERT, a SELECT and an UPDATE per node per query. It was
     * exception-wrapped, so it satisfied "never fail" and nothing noticed it
     * violated "never delay".
     *
     * <p>Prose in a plan does not stop that; a boundary does. Telemetry leaves
     * this path only as a published {@code FederationRequestCompleted} — an event
     * the request thread hands off and forgets. The tempting "simplification" is
     * to call a recorder directly, and here that is not something a future change
     * has to remember not to write: it is something that does not build.
     */
    @ArchTest
    static final ArchRule fanOutReachesNoPersistence =
            noClasses().that().resideInAPackage(ROOT + "query.fanout..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(ROOT + "registry..", "org.springframework.data..",
                            "org.springframework.jdbc..", "javax.sql..", "java.sql..");
}
