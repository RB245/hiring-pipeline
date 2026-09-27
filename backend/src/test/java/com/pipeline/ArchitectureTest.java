package com.pipeline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Production code only. A test for a domain class is entitled to use JUnit and AssertJ;
 * the boundary being defended is the one the shipped application crosses.
 */
@AnalyzeClasses(packages = "com.pipeline", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFreeOfSpring = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule domainIsFreeOfJpa = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..");

    // Fully qualified, not "..api..": that form matches any package with an "api"
    // segment anywhere, including org.assertj.core.api, and would fail on libraries
    // that have nothing to do with this application's layering.
    @ArchTest
    static final ArchRule domainDependsOnNoOuterLayer = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.pipeline.infrastructure..", "com.pipeline.api..");

    // The ports live in application and the adapters implement them, so the arrow runs
    // inward. A use case reaching for a JPA repository would reverse it, and nothing
    // else in the build would notice.
    @ArchTest
    static final ArchRule applicationDoesNotDependOnItsAdapters = noClasses()
            .that().resideInAPackage("com.pipeline.application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.pipeline.infrastructure..", "com.pipeline.api..");
}
