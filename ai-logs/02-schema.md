# 02 - Schema: tables, indexes, append-only stage_event, split database roles

Prompt given: `prompts/02-*.md`

---

<!-- Docker works. Let me settle three Postgres questions empirically before I commit to a plan — the answers change the SQL.

  Ran 1 shell command

All three answered empirically:

- Generated column off an enum works (is_terminal is viable as specified).
- unaccent() cannot be used in an index — "functions in index expression must be marked IMMUTABLE". Needs an immutable wrapper.
- A plain unique index accepted three (1, NULL) rows. So yes — your partial predicate is functionally redundant. More on that below.

Plan

┌─────┬──────────────────────────────────────────────────┬───────────────────────────────────────────────┐
│  #  │                       Step                       │                  Verified by                  │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 1   │ V2__extensions.sql — 4 extensions + an           │ Flyway applies; CREATE INDEX in V5 succeeds   │
│     │ immutable_unaccent wrapper                       │ (it can't, without the wrapper)               │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 2   │ V3__enums.sql — stage, event_type                │ \dT+ shows both with the right labels         │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 3   │ V4__tables.sql — job, candidate, stage_event,    │ Flyway applies; \d matches spec               │
│     │ FKs ON DELETE RESTRICT                           │                                               │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 4   │ V5__indexes.sql — one index per recruiter        │ applies; plus an EXPLAIN probe on seeded data │
│     │ question                                         │  to check each is actually chosen             │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 5   │ V6__stage_event_immutability.sql — the trigger   │ SchemaImmutabilityTest: UPDATE/DELETE as the  │
│     │                                                  │ owner raise P0001                             │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 6   │ V7__roles.sql — pipeline_migrator (owner) +      │ same test: UPDATE/DELETE as pipeline_app fail │
│     │ pipeline_app (narrow grants)                     │  42501; INSERT succeeds                       │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 7   │ CheckConstraintTest — one violating row per      │ each INSERT rejected with the expected        │
│     │ constraint                                       │ SQLSTATE                                      │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 8   │ Wire spring.datasource → pipeline_app,           │ backend boots against compose and             │
│     │ spring.flyway.user → migrator                    │ /actuator/health is UP                        │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 9   │ docs/schema.md — Mermaid ERD + the two notes you │ renders; committed                            │
│     │  asked for                                       │                                               │
├─────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ 10  │ CI: add gradle/actions/wrapper-validation        │ ./gradlew build still green locally; CI       │
│     │                                                  │ itself on push                                │
└─────┴──────────────────────────────────────────────────┴───────────────────────────────────────────────┘

Answers and pushback before I write it

1. The partial predicate on idempotency_key is redundant — keep it anyway. Postgres indexes are NULLS DISTINCT by default, so UNIQUE (candidate_id, idempotency_key) already permits unlimited NULL-keyed rows (I just inserted three). The predicate buys two things that aren't correctness: the index stops carrying entries for rows that can never collide, and it states the intent explicitly, so if someone later writes NULLS NOT DISTINCT (PG15+) the partial clause is what stops every keyless event on a candidate from colliding. Cheap, so I'd keep it — but you should know it's documentation, not enforcement.

2. is_terminal — I'll keep it, but not for the reason it looks like. It's fully derivable from current_stage in the same row, so it stores nothing new. Its value is that Postgres's partial-index predicate prover handles WHERE NOT is_terminal trivially, whereas proving a query's current_stage = 'SCREENING' implies an index predicate of current_stage NOT IN ('HIRED','REJECTED') is something the prover is bad at. So it exists to make one partial index usable, not to save a computation.

3. reached_mask — the index will not do what the phrase "index scan" implies. A btree can't turn reached_mask & 8 = 8 into a range scan; & isn't a searchable operator. The real win is still large and it's the one you named: you avoid the semi-join against stage_event entirely. The index on (job_id, reached_mask) gets you an index-only scan over a 2-byte column instead of a heap scan, which is worth having, but I want to be precise that it's a cheaper scan, not a seek. current_stage/current_stage_since I have no argument with — those are straightforwardly right.

4. Role bootstrapping has a chicken-and-egg problem, and I'm resolving it this way. A migration can't be run by a role it creates. So: pipeline_migrator is a NOLOGIN owner role — it owns every table, type and sequence, which is what gives it DDL authority over them. Flyway connects as the bootstrap superuser (compose's POSTGRES_USER), which is a member of it. pipeline_app is LOGIN and is what Spring's datasource uses. The property you actually care about — the app cannot drop the trigger or alter the table — holds and is testable. The alternative is bootstrapping roles in docker-entrypoint-initdb.d, which makes the migrations non-self-contained and is invisible to Testcontainers. Tell me if you'd rather have that.

pipeline_app's password comes from a Flyway placeholder, not a literal in a committed file.

5. One line beyond your spec, deliberately: a BEFORE TRUNCATE trigger. A row-level BEFORE UPDATE OR DELETE trigger does not fire on TRUNCATE — it's a documented bypass of the layer you care most about. pipeline_app has no TRUNCATE privilege so layer 2 covers it, but you asked for layers that survive each other's failure. Four lines. Say the word and I'll drop it.

6. pipeline_app gets SELECT only on job. If the seeder in file 06 creates the job through the app rather than through SQL, it'll need INSERT and you'll hit a permission error. One-line fix then; flagging now so it isn't a surprise.

Building.


● Write(backend\src\main\resources\db\migration\V2__extensions.sql)
Wrote 16 lines to backend\src\main\resources\db\migration\V2__extensions.sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;
CREATE EXTENSION IF NOT EXISTS citext;

-- unaccent() is declared STABLE, not IMMUTABLE, because a dictionary can be reloaded
-- underneath it. Postgres therefore refuses it in an index expression. Pinning the
-- dictionary by regdictionary removes that variability, which is what lets us promise
-- IMMUTABLE honestly and build the trigram index in V5.
--
… +6 lines

● Write(backend\src\main\resources\db\migration\V3__enums.sql)
Wrote 19 lines to backend\src\main\resources\db\migration\V3__enums.sql
-- Declaration order is load-bearing and permanent: Postgres sorts enum values by the
-- order they were declared, and that order cannot be changed later without rewriting
-- the type. Declared here in pipeline order so ORDER BY current_stage sorts the board
-- left to right for free. REJECTED sits last because it is an exit, not a position.
CREATE TYPE stage AS ENUM (
    'APPLIED',
    'SCREENING',
    'INTERVIEW',
    'OFFER',
    'HIRED',
… +9 lines

● Write(backend\src\main\resources\db\migration\V4__tables.sql)
Wrote 87 lines to backend\src\main\resources\db\migration\V4__tables.sql
CREATE TABLE job (
    id         uuid PRIMARY KEY,
    title      text        NOT NULL,
    -- No DEFAULT now() anywhere in this schema. The application owns a single injected
    -- Clock so that "how long has this candidate been sitting here" is deterministic in
    -- tests; a database-side default would be a second, unfakeable source of time.
    created_at timestamptz NOT NULL
);

CREATE TABLE candidate (
… +78 lines

● Update(backend\src\main\resources\db\migration\V4__tables.sql)
Added 1 line, removed 1 line
    -- not depend on occurred_at, which two events in the same transaction can share.
    seq integer NOT NULL,

    from_stage to_stage_placeholder_unused stage,
    from_stage stage,
    to_stage   stage      NOT NULL,
    event_type event_type NOT NULL,


● Write(backend\src\main\resources\db\migration\V5__indexes.sql)
Wrote 57 lines to backend\src\main\resources\db\migration\V5__indexes.sql
-- Every index below answers a question the recruiter actually asks. Anything that
-- answers no question is not here.


-- "Who is in Interview right now?"
--
-- Not for the board itself: rendering the whole board reads every candidate for the
-- job regardless, so no index helps it. This earns its place on the single-column
-- filter, where it turns a scan of the job into a scan of one stage.
CREATE INDEX candidate_stage_idx ON candidate (job_id, current_stage);
… +48 lines

● Write(backend\src\main\resources\db\migration\V6__stage_event_immutability.sql)
Wrote 24 lines to backend\src\main\resources\db\migration\V6__stage_event_immutability.sql
-- Layer 1 of three. The other two are the privilege split in V7 and the absence of any
-- ON DELETE CASCADE reaching this table in V4. Each is meant to hold if the others are
-- removed by mistake.
--
-- Raised without an explicit ERRCODE so it surfaces as P0001, distinct from the 42501
-- that the V7 revoke produces. The two layers are therefore distinguishable in tests,
-- which is the only way to know both are actually working rather than one masking the
-- other.
CREATE FUNCTION stage_event_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
… +14 lines

● Write(backend\src\main\resources\db\migration\V7__roles.sql)
Wrote 57 lines to backend\src\main\resources\db\migration\V7__roles.sql
-- Layer 2 of three: the application cannot rewrite history even if the V6 trigger is
-- dropped, because dropping it is itself DDL that the application role does not have.
-- That separation is the whole point; a single role holding both would make the
-- revokes below decorative.
--
-- Roles are cluster-wide, not per-database, so these are guarded rather than created
-- blindly. A migration cannot be run by a role it is in the middle of creating, so the
-- bootstrap identity is whoever Flyway connects as; pipeline_migrator is the ownership
-- identity it hands the schema to.
DO $$
… +47 lines

Now the dependencies and tests.

  Read 1 file

● Update(backend\build.gradle.kts)
Added 2 lines

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


● Write(backend\src\test\java\com\pipeline\db\SchemaFixture.java)
Wrote 108 lines to backend\src\test\java\com\pipeline\db\SchemaFixture.java
package com.pipeline.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
… +102 lines

● Write(backend\src\test\java\com\pipeline\db\StageEventImmutabilityTest.java)
Wrote 126 lines to backend\src\test\java\com\pipeline\db\StageEventImmutabilityTest.java
package com.pipeline.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
… +124 lines

● Write(backend\src\test\java\com\pipeline\db\SchemaConstraintTest.java)
Wrote 156 lines to backend\src\test\java\com\pipeline\db\SchemaConstraintTest.java
package com.pipeline.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
… +157 lines

Now the app wiring, then run everything.

● Update(backend\src\main\resources\application.yml)
Added 10 lines, removed 2 lines
    name: pipeline
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/pipeline}
    username: ${DB_USER:pipeline}
    password: ${DB_PASSWORD:pipeline}
    # pipeline_app: SELECT/INSERT/UPDATE only, and no DDL. Created by V7.
    username: ${DB_USER:pipeline_app}
    password: ${DB_PASSWORD:pipeline_app}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    # Migrations run as a separate, privileged identity. If Flyway shared the
    # application's connection the revokes in V7 would mean nothing, since the app
    # could simply migrate the trigger away.
    user: ${DB_MIGRATION_USER:pipeline}
    password: ${DB_MIGRATION_PASSWORD:pipeline}
    placeholders:
      app_password: ${DB_PASSWORD:pipeline_app}

management:
  endpoints:

  Read 1 file

● Update(docker-compose.yml)
Added 4 lines, removed 2 lines
    build: ./backend
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-pipeline}
      DB_USER: ${POSTGRES_USER:-pipeline}
      DB_PASSWORD: ${POSTGRES_PASSWORD:-pipeline}
      DB_USER: pipeline_app
      DB_PASSWORD: ${APP_DB_PASSWORD:-pipeline_app}
      DB_MIGRATION_USER: ${POSTGRES_USER:-pipeline}
      DB_MIGRATION_PASSWORD: ${POSTGRES_PASSWORD:-pipeline}
    ports:
      - "8080:8080"
    depends_on:

  Read 1 file

● Update(.env.example)
Added 4 lines
# Bootstrap superuser. Flyway connects as this; the application does not.
POSTGRES_DB=pipeline
POSTGRES_USER=pipeline
POSTGRES_PASSWORD=pipeline

# Password V7 assigns to pipeline_app, the role Spring's datasource uses.
APP_DB_PASSWORD=pipeline_app

Now run the tests against real Postgres.

  Ran 2 shell commands

Testcontainers 1.20.4 (pinned by the Boot BOM) can't negotiate with Docker 29.8. Let me find a version that can.

  Ran 2 shell commands

● Update(backend\build.gradle.kts)
Added 4 lines
    mavenCentral()
}

// Ahead of the version Boot 3.4 pins. The bundled docker-java in 1.20.x cannot
// negotiate with recent Docker Engine releases and fails to find a daemon at all.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

Docker connects now. Flyway is failing — let me see which migration.

  Ran 1 shell command

The JVM default zone is Asia/Calcutta, a legacy id Postgres 16 rejects outright. The app is meant to run in UTC anyway:

● Update(backend\build.gradle.kts)
Added 4 lines

tasks.test {
    useJUnitPlatform()
    // The application runs in UTC, so the test JVM should too. It also stops pgjdbc
    // forwarding a legacy zone id such as Asia/Calcutta, which Postgres 16 refuses at
    // connection time.
    systemProperty("user.timezone", "UTC")
}

// Leaves a single jar in build/libs so the Dockerfile's COPY glob is unambiguous.

  Ran 2 shell commands

My test row violated two constraints at once; Postgres reported the other. Isolating it:

● Update(backend\src\test\java\com\pipeline\db\SchemaConstraintTest.java)
Added 5 lines, removed 1 line
        }
    }

    /**
     * from_stage is set deliberately: a seq of 0 with a null from_stage also breaks
     * stage_event_first_has_no_from, and Postgres would report that one instead.
     */
    @Test
    void sequenceStartsAtOne() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {


            SQLException thrown = assertThrows(
                    SQLException.class,
                    () -> SchemaFixture.insertEvent(owner, candidateId, 0, null, "APPLIED", "A
PPLIED", null));
                    () -> SchemaFixture.insertEvent(owner, candidateId, 0, "APPLIED", "SCREENI
NG", "ADVANCED", null));
            assertThat(thrown.getSQLState()).isEqualTo("23514");
            assertThat(thrown.getMessage()).contains("stage_event_seq_positive");
        }

  Ran 1 shell command

All 22 green. Now the EXPLAIN probe — an index that exists but is never chosen isn't an index.

  Ran 1 shell command

Two real problems surfaced. Let me dig into both before deciding.

  Ran 1 shell command

Let me settle the trigram question before rewriting the index migration.

  Ran 1 shell command

Confirmed: the same GIN index serves %>, and at word_similarity_threshold = 0.5 "sharam" finds "Priya Sharma" (0.571), while accent folding finds "Zoë Müller" from "muller". Rewriting V5 with what the measurements actually showed.

● Write(backend\src\main\resources\db\migration\V5__indexes.sql)
Added 46 lines, removed 18 lines
-- Every index below answers a question the recruiter actually asks. Anything that
-- answers no question is not here.
-- Every index below answers a question the recruiter actually asks, and every one was
-- checked with EXPLAIN against 50k seeded candidates to confirm the planner picks it.
-- One candidate index was dropped during that exercise; see the note further down.


-- "Who is in Interview right now?"
--
-- Not for the board itself: rendering the whole board reads every candidate for the
-- job regardless, so no index helps it. This earns its place on the single-column
-- filter, where it turns a scan of the job into a scan of one stage.
-- job regardless, so no index helps it. This earns its place on the single-stage
-- filter, where it turns a scan of the job into a scan of one column of the board.
CREATE INDEX candidate_stage_idx ON candidate (job_id, current_stage);

-- "Who has been stuck in Screening for more than a week?"
--
-- current_stage_since trails the stage in the key so the scan is a range within the
-- stage rather than a filter after it. Partial because the question is only ever asked
-- about people still moving: time-in-stage is meaningless once someone is hired or
-- rejected, and excluding them keeps the index proportional to the live pipeline
-- instead of to everyone who ever applied.
-- current_stage_since trails the stage in the key so the cutoff becomes part of the
-- index condition rather than a filter applied afterwards. Partial because the
-- question is only ever asked about people still moving: time-in-stage is meaningless
-- once someone is hired or rejected.
--
-- The query MUST carry an explicit "AND NOT is_terminal". Without it the planner
-- cannot prove the query implies this index's predicate, silently falls back to
-- candidate_stage_idx, and re-checks the date as a filter. That was measured, not
-- assumed. This is the reason is_terminal exists as a stored column at all: the same
-- predicate written as current_stage NOT IN ('HIRED','REJECTED') is beyond what the
-- planner's predicate prover will match.
CREATE INDEX candidate_active_since_idx ON candidate (job_id, current_stage, current_stage_sin
ce)
    WHERE NOT is_terminal;

-- "Who reached Offer but was not hired?"  -> (reached_mask & 8) = 8 AND (reached_mask & 16) =
 0
--
-- Honest about what this does: & is not a searchable operator, so this is not a seek.
-- It is an index-only scan over a two-byte column, which is what replaces the
-- semi-join against stage_event. The saving is the join, not the scan.
CREATE INDEX candidate_reached_idx ON candidate (job_id, reached_mask);
-- Deliberately not indexed. & is not a searchable operator, so a btree on
-- (job_id, reached_mask) is never chosen; adding INCLUDE (id) to tempt an index-only
-- scan did not change the plan either. Both were tried and the planner seq-scanned
-- regardless.
--
-- An expression index on ((reached_mask & 24)) IS used, but it answers exactly one
-- reached/not-reached pair, and the search work needs arbitrary pairs. Fifteen
-- single-purpose indexes is a worse trade than one sequential scan.
--
-- reached_mask still earns its place, just not for the reason an index would suggest.
-- Measured on 50k candidates: masked predicate 8.4ms, equivalent EXISTS/NOT EXISTS
-- pair against stage_event 11.3ms. The gap is modest here because the seed averages
-- under two events per candidate; it widens as histories grow, since the mask query is
-- unaffected by event volume and the semi-join is not. The larger win is that it keeps
-- the search layer writing a predicate on one row instead of generating join pairs.

-- "Find Priya Sharma when I typed sharam."
--
-- Trigram GIN over the accent-folded name. unaccent() itself cannot appear here, hence
-- the immutable wrapper from V2. fuzzystrmatch's levenshtein is deliberately not
-- indexed: it is the tiebreak applied to the handful of rows trigram already returned.
-- Trigram GIN over the accent-folded name. unaccent() cannot appear here directly,
-- hence the immutable wrapper from V2.
--
-- Two things the search layer needs to know, both measured against this index:
-- the plain % operator at its default 0.3 threshold does NOT match "sharam" against
-- "Priya Sharma" (similarity 0.25), because similarity is diluted by the rest of the
-- string. The word-similarity operator %> does match it (0.571) once
-- pg_trgm.word_similarity_threshold is lowered to 0.5, and the planner uses this same
-- index for it. Accent folding works through the wrapper: "muller" finds "Zoë Müller".
--
-- fuzzystrmatch's levenshtein is intentionally not indexed: it is the tiebreak applied
-- to the handful of rows trigram already returned. levenshtein('sharam','sharma') = 2.
CREATE INDEX candidate_name_trgm_idx ON candidate USING gin (immutable_unaccent(full_name) gin
_trgm_ops);

-- "Who moved to Interview since Monday?"

-- "Show me one candidate's timeline."
--
-- No index here on purpose: stage_event_candidate_seq_uq in V4 is already a btree on
-- (candidate_id, seq), which is exactly this lookup in exactly this order. The same is
-- true of the FK column itself and of candidate_job_email_uq for email lookup.
-- (candidate_id, seq), which is this lookup in this order, and EXPLAIN confirms it is
-- what gets used. The same is true of the FK column itself, and of
-- candidate_job_email_uq for email lookup.

-- Retry safety for transitions.
--
Now CI and the docs.
Update(.github\workflows\ci.yml)
Added 4 lines
        with:
          java-version: '21'
          distribution: 'temurin'
      # The wrapper jar was bootstrapped from the gradle/gradle repo rather than
      # generated locally, so its checksum is worth confirming against the published
      # one on every run.
      - uses: gradle/actions/wrapper-validation@v4
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew build
        working-directory: backend

● Write(docs\schema.md)
Wrote 130 lines to docs\schema.md
# Schema

Flyway migrations `V2`–`V7` in `backend/src/main/resources/db/migration`. One concern
per migration: extensions, enums, tables, indexes, immutability, roles.

## ERD

```mermaid
erDiagram
    job ||--o{ candidate : "opens"
… +120 lines

Now the end-to-end check: full test suite plus a real docker compose up from an empty volume.

  Ran 5 shell commands

DNS returns only an IPv6 address for services.gradle.org and containers here have no IPv6 egress. Let me check whether Maven Central is reachable before choosing a fix.

  Ran 1 shell command

Containers can reach Maven Central over IPv4 but Java is preferring the IPv6 route, which has no egress. Testing the fix directly:

  Ran 2 shell commands

The failure is on a redirect. Let me find where it goes and whether that host is reachable from a container.

  Ran 2 shell commands

Docker Hub works; GitHub releases (where the wrapper's distribution now redirects) does not. Switching the build stage to the official Gradle image:

  Read 1 file

● Write(backend\Dockerfile)
Added 11 lines, removed 5 lines
FROM eclipse-temurin:21-jdk AS build
# Gradle comes from the image rather than from the wrapper. The wrapper resolves its
# distribution through a redirect to GitHub releases, which is not reachable from every
# build network, and a container that cannot download Gradle cannot build at all.
# Keep this tag in step with gradle/wrapper/gradle-wrapper.properties; CI still runs
# ./gradlew, so the wrapper remains the version of record for everything except this.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies
# Dependencies resolve in their own layer so that editing a source file does not
# re-download the world on every `docker compose up --build`.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon dependencies
COPY src src
RUN ./gradlew --no-daemon bootJar
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app

  Ran 2 shell commands

  All 7 migrations clean from empty. Let me confirm the two identities are actually in play, not just configured:

  Ran 1 shell command

Live and correct: the app pool holds 10 connections as pipeline_app with only INSERT,SELECT on stage_event, and all three tables are owned by pipeline_migrator.

  Ran 1 shell command

Done. Carry-overs untouched (gradlew still 100755, .gitattributes unmodified, allowEmptyShould left in place).

Verification results — all executed

┌──────────────────────────────────┬─────────────────────────────────────────────────────────────────────┐
│              Check               │                               Result                                │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────┤
│ Flyway migrates cleanly from     │ ✅ docker compose up on a wiped volume: 7/7 applied,                │
│ empty                            │ flyway_schema_history all success = t                               │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────┤
│ /actuator/health                 │ ✅ {"status":"UP"}                                                  │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────┤
│ Testcontainers: UPDATE + DELETE  │ ✅ both 42501; also DROP TRIGGER 42501                              │
│ as the app role both fail        │                                                                     │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────┤
│ Each check constraint rejects a  │ ✅ 9 tests in SchemaConstraintTest                                  │
│ violating row                    │                                                                     │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────┤
│ Real Postgres, not H2            │ ✅ postgres:16 throughout                                           │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────┤
│ ./gradlew build                  │ ✅ 22 tests green                                                   │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────┤
│ Role separation actually in      │ ✅ live pg_stat_activity: 10 app connections as pipeline_app,       │
│ effect                           │ INSERT,SELECT only on stage_event, all tables owned by              │
│                                  │ pipeline_migrator                                                   │
└──────────────────────────────────┴─────────────────────────────────────────────────────────────────────┘

Three findings from the EXPLAIN pass that changed the schema

I seeded 50k candidates and checked every index was actually chosen. Two weren't behaving as assumed.

1. candidate_reached_idx is gone — the planner never used it. & isn't a searchable operator, so the btree on (job_id, reached_mask) was ignored; adding INCLUDE (id) to tempt an index-only scan didn't change the plan either. Both measured, both seq-scanned. An expression index on ((reached_mask & 24)) is used, but it answers exactly one reached/not-reached pair and files 07–08 need arbitrary pairs — fifteen single-purpose indexes is the wrong trade.

Your reasoning for the column still holds, just at a smaller margin than "index scan instead of semi-join" implies: masked predicate 8.4 ms, equivalent EXISTS/NOT EXISTS pair 11.3 ms. My seed averages under two events per candidate, and the gap widens as histories grow since the mask query is indifferent to event volume. I kept reached_mask; I removed the index and wrote the numbers into the migration comment.

2. is_terminal is load-bearing, and the "stuck" query must say so explicitly. Without AND NOT is_terminal in the WHERE clause, the planner can't prove the query implies the partial predicate — it silently falls back to candidate_stage_idx and re-checks the date as a filter. With it, current_stage_since moves into the Index Cond on candidate_active_since_idx. So your instinct was right, but it's a contract on the SQL file 08 writes, not a free win. It's documented in V5 as such.

3. Your motivating search example does not work at default settings. 'sharam' % 'Priya Sharma' scores 0.25, below the 0.3 threshold — no match. The fix is the word-similarity operator %> with pg_trgm.word_similarity_threshold lowered to 0.5, which scores 0.571 and uses this same GIN index. Accent folding works through the wrapper (muller → Zoë Müller), and levenshtein('sharam','sharma') = 2 for the tiebreak. The index is right; the operator and threshold are 07–08's problem, and I've left the measurements in V5 so that work doesn't rediscover them.

Your direct questions

- Partial predicate on idempotency_key: redundant, confirmed. A plain unique index accepted three (1, NULL) rows — Postgres is NULLS DISTINCT by default. Kept anyway: it keeps unindexable rows out of the index and it's what stops keyless events colliding if anyone later writes NULLS NOT DISTINCT. There's a test documenting the behaviour.
- Enum vs lookup table, and adding a stage — in docs/schema.md. Short version: still an enum. ADD VALUE ... BEFORE 'INTERVIEW' handles sort position, so the usual "enums can't be reordered" objection is weaker than it sounds; the real constraints are that a new value can't be used in the transaction that adds it (so Flyway needs two migrations) and that removal is a type rewrite. The answer flips if stages ever become per-job configurable. I also flagged the easy-to-miss second definition: reached_mask bit positions must track the enum, and signed smallint caps you at 15 stages.
- Projection drift — also in docs/schema.md. It can't drift through partial failure (same transaction), but it can through a logic bug, through a path that updates candidate without appending an event (the grants permit this — deliberately), or through backfills. The point I'd stress: because the log is immutable and complete, drift is always repairable by recompute, which makes these columns a cache rather than a second source of truth. I'd add a reconcile-and-diff test now and the same query as a Prometheus metric later; I would not maintain the projection in a trigger, since that puts transition rules in PL/pgSQL where the domain can't see them and the fixed Clock can't fake time for them.

Two environment problems I had to fix to make the above actually run

- Testcontainers 1.20.4 (Boot's pinned version) can't talk to Docker 29.8 — it fails to find a daemon at all. Overridden to 1.21.4 via extra["testcontainers.version"].
- The Docker build couldn't download Gradle. services.gradle.org now 307-redirects to GitHub releases, which times out from containers on this machine (Docker Hub is fine, Maven Central is fine). The build stage now uses the official gradle:8.14.3-jdk21 image instead of the wrapper. Cost: the Gradle version is declared in two places — there's a comment on both. CI still runs ./gradlew, so the wrapper stays the version of record, and wrapper-validation is now in the workflow as you asked.

Things you should know

- spring.flyway.user is now separate from spring.datasource.username. If they shared a connection the revokes would be decorative — the app could migrate the trigger away. Compose passes both; .env.example documents APP_DB_PASSWORD.
- pipeline_app has SELECT only on job. If file 06's seeder creates the job through the app rather than through SQL, you'll get a 42501. One line in a new migration to fix.
- I added a BEFORE TRUNCATE trigger — four lines beyond your spec, because a row-level BEFORE UPDATE OR DELETE trigger does not fire on TRUNCATE. Flagged in file 01's terms: say the word and it goes.
- Test JVM is pinned to UTC (-Duser.timezone=UTC). Your machine's default zone is Asia/Calcutta, a legacy id Postgres 16 rejects at connection time with a FATAL. The Docker image is UTC so compose is unaffected, but ./gradlew bootRun against a local Postgres will hit this — it needs the same flag.
- allowEmptyShould(true) is still on the ArchUnit rules, as instructed. Reminder booked for file 03.
-->

