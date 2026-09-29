plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.pipeline"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Ahead of the version Boot 3.4 pins. The bundled docker-java in 1.20.x cannot
// negotiate with recent Docker Engine releases and fails to find a daemon at all.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Lettuce is optional in the bucket4j module, so it is named here. Spring Data
    // Redis is deliberately absent: it would auto-configure a health indicator for a
    // client nothing else uses, and fail the health check whenever the in-memory
    // limiter is the one selected.
    implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")
    implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.14.0")
    implementation("io.lettuce:lettuce-core")
    implementation("com.github.ben-manes.caffeine:caffeine")
    // 2.8.x tracks Boot 3.5 and registers a swagger-ui resource pattern that Boot 3.4 s
    // PathPatternParser rejects outright, taking the whole context down. 2.7.0 is the
    // release aligned with Boot 3.4.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The application runs in UTC, so the test JVM should too. It also stops pgjdbc
    // forwarding a legacy zone id such as Asia/Calcutta, which Postgres 16 refuses at
    // connection time.
    systemProperty("user.timezone", "UTC")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required = true }
}

/**
 * Coverage is gated on two packages and nowhere else, on purpose.
 *
 * The domain is where the rules live and the search packages are where the logic is; both
 * are pure enough that a gap in them means a behaviour nobody exercised. Everywhere else
 * is adapters, DTOs and wiring, where a coverage number measures how much Spring was
 * started rather than how much was tested — chasing it would buy tests that assert a
 * getter returns what was passed to it.
 *
 * Line ratio rather than instruction: it is the number a human can check against the HTML
 * report without wondering how bytecode was counted.
 */
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("com.pipeline.domain", "com.pipeline.search", "com.pipeline.search.fields")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.bootRun {
    // The same reason, and the same failure: without it, a developer whose machine is set
    // to one of the zone ids Postgres dropped cannot start the application at all, while
    // the tests above pass. The container image is already UTC, so this only ever matters
    // for running it straight from a workstation.
    systemProperty("user.timezone", "UTC")
}

// Leaves a single jar in build/libs so the Dockerfile's COPY glob is unambiguous.
tasks.jar {
    enabled = false
}
