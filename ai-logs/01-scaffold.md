# 01 - Scaffold: repo layout, Docker Compose, Flyway baseline, ArchUnit boundary

Prompt given: `prompts/01-*.md`

---

<!-- Plan first, then concerns, then I build.

Plan

┌─────┬────────────────────────────────────────────────────┬─────────────────────────────────────────────┐
│  #  │                        Step                        │                 Verified by                 │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 1   │ Probe toolchain (java/gradle/docker/node)          │ versions print; anything missing gets named │
│     │                                                    │  now, not when it blocks                    │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│     │ Root scaffold: /backend /frontend /docs /ai-logs,  │                                             │
│ 2   │ .gitignore, .env.example, Makefile,                │ directory listing shows all of them         │
│     │ docker-compose.yml                                 │                                             │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│     │ Gradle (Kotlin DSL) + wrapper; Boot 3.4, Java 21,  │ ./gradlew --version and ./gradlew           │
│ 3   │ deps: web, data-jpa, actuator, flyway, postgres,   │ compileJava succeed                         │
│     │ micrometer-prometheus, archunit-junit5             │                                             │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│     │ Packages domain/ application/ infrastructure/ api/ │                                             │
│ 4   │  (each with package-info.java so the dirs survive  │ compiles                                    │
│     │ git), PipelineApplication, ClockConfig →           │                                             │
│     │ Clock.systemUTC()                                  │                                             │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 5   │ application.yml: env-driven datasource, ddl-auto:  │ steps 8–9                                   │
│     │ validate, actuator exposing health,prometheus      │                                             │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 6   │ Flyway V1__baseline.sql (empty)                    │ backend log shows 1 migration applied;      │
│     │                                                    │ flyway_schema_history has version 1         │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│     │ Tests: ArchitectureTest (3 rules),                 │ ./gradlew test green; and I temporarily add │
│ 7   │ ClockConfigTest, FixedClockConfig + a test proving │  a Spring import to a domain class to       │
│     │  the fixed clock is what gets injected             │ confirm the ArchUnit test actually goes     │
│     │                                                    │ red, then revert                            │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│     │ Backend Dockerfile (multi-stage) + compose: pg16 & │ docker compose up -d → /actuator/health =   │
│ 8   │  redis7 with healthchecks, backend depends_on:     │ UP, /actuator/prometheus = 200              │
│     │ service_healthy                                    │                                             │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│ 9   │ create-next-app defaults (TS, App Router, in       │ npm run build succeeds                      │
│     │ /frontend)                                         │                                             │
├─────┼────────────────────────────────────────────────────┼─────────────────────────────────────────────┤
│     │ .github/workflows/ci.yml — setup-java 21 +         │ can't execute Actions locally; I'll         │
│ 10  │ ./gradlew build                                    │ validate the YAML and say plainly that CI   │
│     │                                                    │ itself is unverified until you push         │
└─────┴────────────────────────────────────────────────────┴─────────────────────────────────────────────┘

What I'd do differently / flag

1. Redis gets a container but no Spring dependency. Nothing consumes it until 07/08. Adding spring-boot-starter-data-redis now buys you a Redis health indicator that can fail your health check for a service you aren't using yet. I'll run the container (so docker compose up is true from day one, as you asked) and wire the dep when something actually needs it. Say the word if you want it in now.
2. Frontend is not in compose. You enumerated Postgres, Redis, backend — but also said compose should be the only command needed. I'm taking the literal reading: frontend runs via npm run dev, and there's a make target for it. Easy to add a frontend service later; tell me if you want it now.
3. The ArchUnit rule is close to vacuous today — domain has no real classes. ArchUnit 1.x fails a rule that matches nothing, so package-info.java is what keeps it from erroring. The rule only earns its keep in 02. That's why step 7 includes me deliberately breaking it to prove it bites.
4. No @SpringBootTest context-load test. With JPA + Flyway on the classpath, booting the full context needs a live Postgres, which means Testcontainers — more machinery than this file warrants. The clock tests boot only ClockConfig. Full-context testing arrives with the schema in 02.
5. Makefile on Windows: you're on Win11; make isn't there by default. I'll write it as asked (CI and reviewers will use it) but the targets are thin wrappers you can run directly.
6. Repo isn't a git repo yet. I won't git init unless you ask.

Building now.
Environment findings that change the plan:

- Java 24 is installed, not 21. I'll pin languageVersion = 21 via a Gradle toolchain and add the foojay resolver so Gradle fetches a JDK 21 itself — one line in settings.gradle.kts, no manual install for you.
- No Docker on this machine. I'll write docker-compose.yml and the Dockerfile, but I cannot run step 8's verification here. I'll say exactly that rather than claim it's green.
- No Gradle installed — I'll bootstrap the wrapper by fetching it.
- Node 22 and git are present; directory is empty.

  Ran 1 shell command

Network is available. Bootstrapping the Gradle wrapper.

  Ran 1 shell command

Now the Gradle files.

● Write(D:\Hiring pipeline\backend\gradle\wrapper\gradle-wrapper.properties)
Wrote 7 lines to ..\Hiring pipeline\backend\gradle\wrapper\gradle-wrapper.properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists

● Write(D:\Hiring pipeline\backend\settings.gradle.kts)
Wrote 6 lines to ..\Hiring pipeline\backend\settings.gradle.kts
plugins {
    // Lets Gradle download a JDK 21 toolchain when the local JDK is a different version.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "pipeline"

● Write(D:\Hiring pipeline\backend\build.gradle.kts)
Wrote 36 lines to ..\Hiring pipeline\backend\build.gradle.kts
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.pipeline"
version = "0.0.1-SNAPSHOT"

java {
… +26 lines

● Write(D:\Hiring pipeline\backend\src\main\java\com\pipeline\PipelineApplication.java)
Wrote 12 lines to ..\Hiring pipeline\backend\src\main\java\com\pipeline\PipelineApplication.java
package com.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
… +2 lines

● Write(D:\Hiring pipeline\backend\src\main\java\com\pipeline\domain\package-info.java)
Wrote 5 lines to ..\Hiring pipeline\backend\src\main\java\com\pipeline\domain\package-info.java
/**
 * Pure Java domain model. No Spring, no JPA, no dependency on infrastructure or api.
 * Enforced by {@code com.pipeline.ArchitectureTest}.
 */
package com.pipeline.domain;

● Write(D:\Hiring pipeline\backend\src\main\java\com\pipeline\application\package-info.java)
Wrote 4 lines to ..\Hiring pipeline\backend\src\main\java\com\pipeline\application\package-info.java
/**
 * Use cases and the port interfaces they depend on.
 */
package com.pipeline.application;

● Write(D:\Hiring pipeline\backend\src\main\java\com\pipeline\infrastructure\package-info.java)
Wrote 4 lines to ..\Hiring pipeline\backend\src\main\java\com\pipeline\infrastructure\package-info.java
/**
 * Adapters: JPA persistence, clock, and other outbound implementations of application ports.
 */
package com.pipeline.infrastructure;

● Write(D:\Hiring pipeline\backend\src\main\java\com\pipeline\api\package-info.java)
Wrote 4 lines to ..\Hiring pipeline\backend\src\main\java\com\pipeline\api\package-info.java
/**
 * HTTP layer: controllers, DTOs, exception handling.
 */
package com.pipeline.api;

● Write(D:\Hiring pipeline\backend\src\main\java\com\pipeline\infrastructure\ClockConfig.java)
Wrote 14 lines to ..\Hiring pipeline\backend\src\main\java\com\pipeline\infrastructure\ClockConfig.java
package com.pipeline.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
… +4 lines

● Write(D:\Hiring pipeline\backend\src\main\resources\application.yml)
Wrote 19 lines to ..\Hiring pipeline\backend\src\main\resources\application.yml
spring:
  application:
    name: pipeline
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/pipeline}
    username: ${DB_USER:pipeline}
    password: ${DB_PASSWORD:pipeline}
  jpa:
    hibernate:
      ddl-auto: validate
… +9 lines
Write(D:\Hiring pipeline\backend\src\main\resources\db\migration\V1__baseline.sql)
Wrote 1 line to ..\Hiring pipeline\backend\src\main\resources\db\migration\V1__baseline.sql
-- Baseline. Intentionally empty; real schema lands in the next migration.

Now the tests.

● Write(D:\Hiring pipeline\backend\src\test\java\com\pipeline\ArchitectureTest.java)
Wrote 32 lines to ..\Hiring pipeline\backend\src\test\java\com\pipeline\ArchitectureTest.java
package com.pipeline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.pipeline")
class ArchitectureTest {
… +22 lines

● Write(D:\Hiring pipeline\backend\src\test\java\com\pipeline\infrastructure\FixedClockConfig.java)
Wrote 23 lines to ..\Hiring pipeline\backend\src\test\java\com\pipeline\infrastructure\FixedClockConfig.java
package com.pipeline.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
… +13 lines

● Write(D:\Hiring pipeline\backend\src\test\java\com\pipeline\infrastructure\ClockConfigTest.java)
Wrote 31 lines to ..\Hiring pipeline\backend\src\test\java\com\pipeline\infrastructure\ClockConfigTest.java
package com.pipeline.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
… +21 lines

Now let me get the build running before writing the rest.

  Ran 1 shell command

Build passes. Let me confirm which tests actually ran, then prove the ArchUnit rule bites.

  Ran 1 shell command

5 tests, all green. Now the negative check — a throwaway domain class that violates all three rules:

  Ran 1 shell command

All three rules fire. Reverting the throwaway class:

  Ran 1 shell command

Now Docker, compose, and the root files.

● Write(D:\Hiring pipeline\backend\Dockerfile)
Wrote 13 lines to ..\Hiring pipeline\backend\Dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
… +3 lines

● Write(D:\Hiring pipeline\docker-compose.yml)
Wrote 43 lines to ..\Hiring pipeline\docker-compose.yml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-pipeline}
      POSTGRES_USER: ${POSTGRES_USER:-pipeline}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-pipeline}
    ports:
      - "5432:5432"
    volumes:
… +33 lines

● Write(D:\Hiring pipeline\.env.example)
Wrote 3 lines to ..\Hiring pipeline\.env.example
POSTGRES_DB=pipeline
POSTGRES_USER=pipeline
POSTGRES_PASSWORD=pipeline

● Write(D:\Hiring pipeline\Makefile)
Wrote 13 lines to ..\Hiring pipeline\Makefile
.PHONY: up down test frontend

up:
  docker compose up --build

down:
  docker compose down

test:
  cd backend && ./gradlew test
… +3 lines

● Write(D:\Hiring pipeline\.gitignore)
Wrote 14 lines to ..\Hiring pipeline\.gitignore
.env

# Backend
backend/build/
backend/.gradle/

# Frontend
frontend/node_modules/
frontend/.next/
… +4 lines

● Write(D:\Hiring pipeline\.github\workflows\ci.yml)
Wrote 16 lines to ..\Hiring pipeline\.github\workflows\ci.yml
name: CI

on: [push, pull_request]

jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
… +6 lines

Now the frontend.

  Ran 2 shell commands

Next 16 shipped — @latest gave me 16.3.6, but you specified Next 15. Redoing pinned to 15:

  Ran 7 shell commands

● Update(D:\Hiring pipeline\backend\Dockerfile)
Added 1 line, removed 1 line
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies
COPY src src
RUN ./gradlew --no-daemon bootJar


  Ran 3 shell commands

Now the full CI command, exactly as the workflow runs it:

  Ran 1 shell command

The Docker build would break: build/libs/*.jar matches two jars. Disabling the plain jar, which is the standard Boot fix:

● Update(D:\Hiring pipeline\backend\build.gradle.kts)
Added 5 lines
tasks.test {
    useJUnitPlatform()
}

// Leaves a single jar in build/libs so the Dockerfile's COPY glob is unambiguous.
tasks.jar {
    enabled = false
}

  Ran 1 shell command

Docker isn't available here, so I can't run compose. But I can still verify the Actuator wiring by booting the jar with the datasource autoconfig excluded:

  Ran 3 shell commands

Scaffold is built. Stopping here as asked.

Verification results

┌──────────────────────────────┬─────────────────────────────────────────────────────────────────────────┐
│             Step             │                                 Status                                  │
├──────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Repo layout, .gitignore,     │                                                                         │
│ .env.example, Makefile,      │ ✅ listed above                                                         │
│ compose                      │                                                                         │
├──────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Package layout under         │ ✅ compiles                                                             │
│ com.pipeline                 │                                                                         │
├──────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│                              │ ✅ 3 rules pass — and I added a domain class importing Spring +         │
│ ArchUnit test                │ jakarta.persistence + infrastructure, saw all three go FAILED, then     │
│                              │ deleted it                                                              │
├──────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│                              │ ⚠️ written, not executed — no Docker on this machine. YAML parses;      │
│ docker-compose.yml           │ services postgres/redis/backend, both healthchecks present, backend     │
│                              │ gated on service_healthy for both                                       │
├──────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Flyway baseline              │ ⚠️ unverified — needs a live Postgres, i.e. Docker                      │
├──────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│                              │ ✅ booted the jar with datasource autoconfig excluded: /actuator/health │
│ Actuator health + Prometheus │  → {"status":"UP"}, /actuator/prometheus → 200 with real metrics,       │
│                              │ /actuator/env → 404 (exposure is correctly limited)                     │
├──────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Clock bean + fixed test      │ ✅ ClockConfigTest asserts UTC; nested @SpringBootTest asserts          │
│ clock                        │ @Import(FixedClockConfig) is what gets injected                         │
├──────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ ./gradlew test / build       │ ✅ 5 tests, green; build produces one boot jar                          │
├──────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ CI                           │ ⚠️ YAML parses and the exact command (./gradlew build in backend)       │
│                              │ passes locally, but GitHub Actions itself is unverified until you push  │
└──────────────────────────────┴─────────────────────────────────────────────────────────────────────────┘

Decisions you should know about

1. Next 15.5.26, not 16. create-next-app@latest gave me 16.3.6 — I tore it out and pinned @15 to match your spec. Next 16 is out if you'd rather be current.
2. Java toolchain. Your machine has JDK 24, not 21. settings.gradle.kts uses the foojay resolver so Gradle downloads a JDK 21 toolchain itself; build.gradle.kts pins languageVersion = 21. Nothing for you to install.
3. Gradle wrapper bootstrapped from gradle/gradle at tag v8.14.3 (no local Gradle to run gradle wrapper). 8.14.3 is the first line that supports running on Java 24.
4. Two fixes I made that you didn't ask for, both required to make what you did ask for work:
  - tasks.jar { enabled = false } — otherwise build/libs/ holds two jars and the Dockerfile's COPY *.jar app.jar fails on multiple matches.
  - RUN chmod +x gradlew in the Dockerfile — the exec bit doesn't reliably survive Windows → image.
5. create-next-app ran git init inside frontend/. I deleted that nested .git so your root repo isn't confused. Root still isn't a git repo — say the word and I'll git init.
6. allowEmptyShould(true) on the ArchUnit rules. ArchUnit 1.x fails a rule matching zero classes, and domain has none yet (package-info.java alone emits no .class). The flag is commented as temporary; it comes off the moment 02 puts real types in domain.

Still open from my earlier flags

- Redis runs but nothing consumes it — no spring-boot-starter-data-redis yet, deliberately.
- Frontend isn't a compose service — make frontend runs it. Both are one-line changes whenever you want them.
- Neither docker compose up nor the Flyway baseline can be confirmed from this machine. First thing worth doing on a box with Docker: docker compose up --build, then check for Successfully applied 1 migration in the backend log.
-->

