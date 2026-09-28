# 05 - HTTP API: endpoints, idempotency, RFC 9457 problem details

Prompt given: `prompts/05-*.md`

---

<!-- Let me settle three things that the endpoint list leaves open, then plan.

Where does jobId come from? No endpoint takes one, and the brief says a single job opening. So the API has to resolve "the" job itself. I'll add a JobReader port returning the single job row. That creates a bootstrap gap — a fresh docker compose up has no job, so POST /candidates will fail until file 06 seeds one. Tests seed it themselves, as file 04 does.

What does the transition endpoint return? It must be the event, not the candidate's current state. Your test requires both replay responses to match; if the body carried the candidate's live state, an unrelated concurrent change would make a replay differ from the original. So: {candidateId, fromStage, toStage, eventType, occurredAt, reason}, derived identically whether it's fresh or replayed. The UI refetches the candidate.

Concurrent same-key requests are a 409, not a replay. The sequential replay you asked for is handled by a check-then-act inside the transaction. Two simultaneous requests with the same key can't both be served the original, because the loser's transaction is already rollback-only when the unique index fires, and re-reading needs a fresh transaction — which means self-invocation gymnastics or a second bean. I'm mapping it to 409 and documenting that the client's next retry gets the original. Say the word if you want the outer-retry wiring instead.

Plan

┌─────┬──────────────────────────────────────────────────────────┬───────────────────────────────────────┐
│  #  │                           Step                           │              Verified by              │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│ 1   │ Add springdoc-openapi-starter-webmvc-ui +                │ compiles; /swagger-ui/index.html      │
│     │ spring-boot-starter-validation                           │ returns 200 with all 7 operations     │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│     │ application/: StaleCandidateStateException, JobReader,   │                                       │
│ 2   │ EventReader.findByIdempotencyKey,                        │ existing 88 tests still green         │
│     │ CandidateReader.summary/allIds, CandidateSummary gains   │                                       │
│     │ phone+source                                             │                                       │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│     │ TransitionCandidate takes expectedCurrentStage and       │ unit-level: replay returns the        │
│ 3   │ replays on a known key                                   │ original event, stale expectation     │
│     │                                                          │ throws                                │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│ 4   │ RebuildAllProjections use case                           │ returns rebuilt + changed counts      │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│ 5   │ infrastructure/: JpaJobReader, repo + adapter additions  │ context boots, schema still validates │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│ 6   │ api/: DTOs, 3 controllers, CursorCodec, DurationFormat,  │ every endpoint answers end to end     │
│     │ CorrelationIdFilter                                      │ against Testcontainers                │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│ 7   │ One @RestControllerAdvice extending                      │ 422/404/409/400 all produced there,   │
│     │ ResponseEntityExceptionHandler                           │ zero try/catch in controllers         │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│ 8   │ Idempotency replay test                                  │ same key twice → exactly one event    │
│     │                                                          │ row, two identical bodies             │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│ 9   │ Concurrent transition test                               │ two real HTTP calls on a random port  │
│     │                                                          │ → exactly one 201, exactly one 409    │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│     │                                                          │ 4 error types asserted to carry the   │
│ 10  │ Problem Details consistency test                         │ same field set: type, title, status,  │
│     │                                                          │ detail, instance, correlationId       │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│ 11  │ Illegal transition body carries legal alternatives       │ 422 with legalTargets:                │
│     │                                                          │ ["SCREENING","REJECTED"]              │
├─────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────┤
│ 12  │ ./gradlew build + compose smoke                          │ full suite green, health UP, Swagger  │
│     │                                                          │ renders                               │
└─────┴──────────────────────────────────────────────────────────┴───────────────────────────────────────┘

Pushback before I start

expectedCurrentStage will be required. Your example always sends it, and making it optional adds a branch that exists only for callers who skipped a read. One annotation to relax if you disagree.

The correlation ID needs a filter, and that's arguably file 06's territory. You asked for it in the error body, so I'm adding a minimal one here: accept an inbound X-Correlation-Id or mint one, echo it on the response, expose it to the advice. When file 06 does observability it should extend this into MDC rather than replace it.

The admin endpoint will return {rebuilt, changed}, not just a count. changed is the number whose projection actually differed from the log — which is the entire question that endpoint exists to answer. One extra comparison.

I'm adding phone and source to CandidateSummary rather than introducing a parallel CandidateDetail. It touches a file-04 record, but a second near-identical read model is worse than two extra fields.

Building.

 Update(backend\build.gradle.kts)
Added 2 lines
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

Now the application layer changes.

  Ran 1 shell command

● Write(backend\src\main\java\com\pipeline\application\CandidateSummary.java)
Added 3 lines, removed 1 line
import java.time.Instant;
import java.util.UUID;

/** A candidate as the board and the list show them. */
/** A candidate as the board, the list and the detail endpoint show them. */
public record CandidateSummary(
        UUID id,
        String fullName,
        String email,
        String phone,
        String source,
        Stage currentStage,
        Instant currentStageSince,
        Instant createdAt) {

  Ran 1 shell command

● Write(backend\src\main\java\com\pipeline\application\TransitionCandidate.java)
Added 31 lines, removed 4 lines
import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageEvent;
import com.pipeline.domain.StageTransitions;
import com.pipeline.domain.TransitionDecision;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


    private final CandidateReader reader;
    private final CandidateWriter writer;
    private final EventReader events;
    private final StageTransitions transitions;

    public TransitionCandidate(CandidateReader reader, CandidateWriter writer, StageTransition
s transitions) {
    public TransitionCandidate(
            CandidateReader reader, CandidateWriter writer, EventReader events, StageTransitio
ns transitions) {
        this.reader = reader;
        this.writer = writer;
        this.events = events;
        this.transitions = transitions;
    }

    /**
     * @param expectedCurrentStage where the caller last saw the candidate; a mismatch
     *     means they are acting on a stale view and nothing is written
     * @throws com.pipeline.domain.IllegalStageTransitionException if the move is not
     *     permitted, carrying the moves that would have been
     */
    @Transactional
    public TransitionDecision transition(
            UUID candidateId, Stage target, Actor actor, String reason, String idempotencyKey)
 {
    public TransitionOutcome transition(
            UUID candidateId,
            Stage expectedCurrentStage,
            Stage target,
            Actor actor,
            String reason,
            String idempotencyKey) {

        // Checked first and inside the transaction, so a retry that arrives after the
        // original committed sees it. Two genuinely simultaneous requests with one key
        // are a different case: the unique index rejects the loser, and its next retry
        // lands here and gets the original.
        if (idempotencyKey != null) {
            Optional<StageEvent> already = events.findByIdempotencyKey(candidateId, idempotenc
yKey);
            if (already.isPresent()) {
                return new TransitionOutcome(already.get(), true);
            }
        }

        Candidate candidate = reader.load(candidateId).orElseThrow(() -> new CandidateNotFound
Exception(candidateId));

        if (expectedCurrentStage != null && candidate.currentStage() != expectedCurrentStage)
{
            throw new StaleCandidateStateException(candidateId, expectedCurrentStage, candidat
e.currentStage());
        }

        TransitionDecision decision = transitions.transition(candidate, target, actor, reason,
 idempotencyKey);

        writer.appendEvent(decision.event());
        writer.updateProjection(
                candidateId, decision.newStage(), decision.event().occurredAt(), decision.reac
hedMask());

        return decision;
        return new TransitionOutcome(decision.event(), false);
    }
}

● Write(backend\src\main\java\com\pipeline\application\RebuildAllProjections.java)
Wrote 44 lines to backend\src\main\java\com\pipeline\application\RebuildAllProjections.java
package com.pipeline.application;

import com.pipeline.domain.Candidate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Sweeps every candidate in the job. Reports how many actually differed from their log,
 * because "how much drift is there" is the only question this endpoint exists to answer.
… +35 lines

Now the infrastructure side.

  Ran 2 shell commands

The board's javadoc got orphaned above summary by my insertion. Fixing:
 Update(backend\src\main\java\com\pipeline\infrastructure\JpaCandidateReader.java)
Added 5 lines, removed 5 lines
        return candidates.findById(candidateId).map(JpaCandidateReader::toDomain);
    }

    /**
     * Grouped in memory on purpose. Rendering the board reads every candidate for the
     * job whatever happens, so a GROUP BY plus one query per column would be more round
     * trips for the same rows. Empty columns are included so the board keeps its shape.
     */
    @Override
    public Optional<CandidateSummary> summary(UUID candidateId) {
        return candidates.findById(candidateId).map(JpaCandidateReader::toSummary);

        return candidates.idsForJob(jobId);
    }

    /**
     * Grouped in memory on purpose. Rendering the board reads every candidate for the
     * job whatever happens, so a GROUP BY plus one query per column would be more round
     * trips for the same rows. Empty columns are included so the board keeps its shape.
     */
    @Override
    public List<BoardColumn> board(UUID jobId) {
        List<CandidateEntity> all = candidates.findByJobIdOrderByCreatedAtDescIdDesc(jobId);

Now the API layer — supporting pieces first.

  Ran 1 shell command

Now the DTOs.

  Ran 1 shell command

Now the controllers.

● Write(backend\src\main\java\com\pipeline\api\CurrentActor.java)
Wrote 19 lines to backend\src\main\java\com\pipeline\api\CurrentActor.java
package com.pipeline.api;

import com.pipeline.domain.Actor;
import org.springframework.stereotype.Component;

/**
 * There is no authentication yet, so every event is attributed to the same placeholder.
 * This exists as a single seam rather than a constant repeated in two controllers: when
 * auth arrives, one method changes and the audit trail starts naming real people.
 */
… +9 lines

● Write(backend\src\main\java\com\pipeline\api\CandidateController.java)
Wrote 150 lines to backend\src\main\java\com\pipeline\api\CandidateController.java
package com.pipeline.api;

import com.pipeline.application.CandidateNotFoundException;
import com.pipeline.application.CandidatePage;
import com.pipeline.application.CandidateProfile;
import com.pipeline.application.CandidateReader;
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.EventReader;
import com.pipeline.application.JobReader;
import com.pipeline.application.NoJobConfiguredException;
… +154 lines

  Ran 1 shell command

Now the single error handler.

● Write(backend\src\main\java\com\pipeline\api\ProblemDetails.java)
Wrote 129 lines to backend\src\main\java\com\pipeline\api\ProblemDetails.java
package com.pipeline.api;

import com.pipeline.application.CandidateNotFoundException;
import com.pipeline.application.NoJobConfiguredException;
import com.pipeline.application.StaleCandidateStateException;
import com.pipeline.domain.IllegalStageTransitionException;
import com.pipeline.domain.Stage;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
… +133 lines

  Ran 1 shell command

Now restructuring the test base so the API tests share one container with file 04's, then writing the tests.

  Ran 1 shell command

● Write(backend\src\test\java\com\pipeline\support\IntegrationTest.java)
Wrote 95 lines to backend\src\test\java\com\pipeline\support\IntegrationTest.java
package com.pipeline.support;

import com.pipeline.db.SchemaFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
… +88 lines

I wrote a broken placeholder there, and it exposed a real design problem: singleJobId() had no ordering, so it could return a different job between calls. Fixing both.

● Update(backend\src\main\java\com\pipeline\infrastructure\JpaJobReader.java)
Added 7 lines, removed 1 line
        this.jobs = jobs;
    }

    /**
     * Ordered, not merely limited. Without an ORDER BY, Postgres is free to return a
     * different row each call, and "the" job opening would quietly stop being one job.
     */
    @Override
    public Optional<UUID> singleJobId() {
        List<JobEntity> found = jobs.findAll(PageRequest.ofSize(1)).getContent();
        List<JobEntity> found = jobs
                .findAll(PageRequest.ofSize(1).withSort(Sort.by("createdAt").and(Sort.by("id")
)))
                .getContent();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0).id);
    }
}

  Ran 1 shell command

That UUID expression was a silly way to write a constant. Replacing it:

● Update(backend\src\test\java\com\pipeline\support\IntegrationTest.java)
Added 1 line, removed 1 line
     * DELETE and candidate is pinned by its foreign key � so the fixture has to be
     * stable rather than fresh.
     */
    protected static final UUID CANONICAL_JOB = UUID.fromString("00000000-0000-0000-0000-00000
000j0b1".replace("j", "1").replace("b", "1"));
    protected static final UUID CANONICAL_JOB = UUID.fromString("00000000-0000-0000-0000-00000
0000001");

    protected static void ensureCanonicalJob() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();

  Ran 1 shell command

The 6th parameter broke file 04's call sites. Patching them to pass null (they don't test the stale check) and fixing the import order I disturbed.

  Ran 1 shell command

Now the API tests.

● Write(backend\src\test\java\com\pipeline\api\ApiTest.java)
Wrote 45 lines to backend\src\test\java\com\pipeline\api\ApiTest.java
package com.pipeline.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.support.IntegrationTest;
import com.pipeline.support.MutableClock;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
… +35 lines

● Write(backend\src\test\java\com\pipeline\api\CandidateApiTest.java)
Wrote 149 lines to backend\src\test\java\com\pipeline\api\CandidateApiTest.java
package com.pipeline.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
… +141 lines

● Write(backend\src\test\java\com\pipeline\api\TransitionApiTest.java)
Wrote 135 lines to backend\src\test\java\com\pipeline\api\TransitionApiTest.java
package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
… +134 lines

● Write(backend\src\test\java\com\pipeline\api\ProblemDetailsApiTest.java)
Wrote 101 lines to backend\src\test\java\com\pipeline\api\ProblemDetailsApiTest.java
package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
… +92 lines

Now the concurrency test on a real port, and the OpenAPI check.

● Write(backend\src\test\java\com\pipeline\api\ConcurrentTransitionTest.java)
Wrote 127 lines to backend\src\test\java\com\pipeline\api\ConcurrentTransitionTest.java
package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.support.IntegrationTest;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
… +119 lines

● Write(backend\src\test\java\com\pipeline\api\OpenApiTest.java)
Wrote 60 lines to backend\src\test\java\com\pipeline\api\OpenApiTest.java
package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
… +50 lines

  Ran 1 shell command

Every API test fails identically — a context problem, not test logic.

  Ran 3 shell commands

springdoc 2.8.17 tracks Boot 3.5; its resource pattern is invalid under Boot 3.4's parser. Dropping to the 2.7.x line that targets Boot 3.4.

  Ran 3 shell commands

115 tests, all green. Final check — full build plus a live walkthrough of every endpoint against compose.

  Ran 2 shell commands

Caught a real bug the test missed: the two idempotent responses differ. The first returns the in-memory event with nanosecond precision; the replay returns it from Postgres, which stores microseconds. My test clock sat on a whole second, so truncation never showed.

  Ran 2 shell commands

Postgres rounds to the nearest microsecond, so the stored value isn't even a prefix of the original. Fixing at the point where events are stamped.

● Update(backend\src\main\java\com\pipeline\domain\Candidate.java)
Added 1 line, removed 1 line
     * place a {@code from_stage} of null is correct.
     */
    public static CandidateCreation register(UUID id, Actor actor, Clock clock) {
        Instant now = clock.instant();
        Instant now = EventTime.stamp(clock);
        Candidate candidate = new Candidate(id, Stage.APPLIED, now, Stage.APPLIED.bit());
        StageEvent firstEvent = new StageEvent(
                id, null, Stage.APPLIED, Stage.APPLIED.entryEventType(), now, actor, null, nul
l);

● Write(backend\src\main\java\com\pipeline\domain\EventTime.java)
Wrote 24 lines to backend\src\main\java\com\pipeline\domain\EventTime.java
package com.pipeline.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Events are stamped at microsecond resolution, which is the resolution the log is kept
 * at. Recording anything finer would be a lie the moment it is written down: the store
 * rounds to the nearest microsecond, so an event read back would not equal the one just
… +14 lines

  Ran 1 shell command

Now make the replay test actually guard this — the old one sat on a whole second.

● Update(backend\src\test\java\com\pipeline\api\TransitionApiTest.java)
Added 7 lines
    /**
     * The double-click. Same key twice must produce one event and two identical
     * responses, so the caller cannot tell which of them did the work.
     *
     * <p>The clock is deliberately set to a sub-microsecond instant. The first response
     * is built from the event in memory and the replay is read back from Postgres,
     * which keeps microseconds and rounds; without truncation at the point the event is
     * stamped, the two bodies differ in the last few digits of occurredAt and nothing
     * coarser would ever notice.
     */
    @Test
    void replayingAnIdempotencyKeyReturnsTheOriginalAndWritesNothingNew() throws Exception {
        clock.set(Instant.parse("2025-03-01T09:00:00.347750677Z"));
        UUID id = createCandidate("Double Clicked");
        String key = UUID.randomUUID().toString();


  Ran 1 shell command

Confirming the strengthened test actually catches it — reverting the fix temporarily:

  Ran 1 shell command

Clock state was leaking between test classes — the nanosecond instant I set in one class bled into another. Resetting it per test in the shared base, which those classes should have had anyway.

  Ran 4 shell commands

Done.

Verification results — all executed

┌───────────────────────────┬────────────────────────────────────────────────────────────────────────────┐
│           Check           │                                   Result                                   │
├───────────────────────────┼────────────────────────────────────────────────────────────────────────────┤
│ Every endpoint end to end │ ✅ all 7 exercised live against compose, and in MockMvc against            │
│                           │ Testcontainers                                                             │
├───────────────────────────┼────────────────────────────────────────────────────────────────────────────┤
│ Idempotency replay        │ ✅ same key twice → one event row, byte-identical bodies (verified live    │
│                           │ too)                                                                       │
├───────────────────────────┼────────────────────────────────────────────────────────────────────────────┤
│ Concurrent transitions    │ ✅ two real HTTP calls on a random port → exactly one 201, one 409, two    │
│                           │ events total                                                               │
├───────────────────────────┼────────────────────────────────────────────────────────────────────────────┤
│ Illegal transition → 422  │ ✅ legalTargets: ["SCREENING","REJECTED"]; terminal candidates return []   │
│ with alternatives         │                                                                            │
├───────────────────────────┼────────────────────────────────────────────────────────────────────────────┤
│ Problem Details           │ ✅ 6 error paths asserted to carry                                         │
│ consistency               │ type/title/status/detail/instance/correlationId and                        │
│                           │ application/problem+json                                                   │
├───────────────────────────┼────────────────────────────────────────────────────────────────────────────┤
│ Swagger UI                │ ✅ renders; /v3/api-docs documents all 7 operations, the Idempotency-Key   │
│                           │ header and the 409                                                         │
├───────────────────────────┼────────────────────────────────────────────────────────────────────────────┤
│ ./gradlew build           │ ✅ 115 tests                                                               │
├───────────────────────────┼────────────────────────────────────────────────────────────────────────────┤
│ docker compose up         │ ✅ health UP, 7 migrations, full walkthrough                               │
└───────────────────────────┴────────────────────────────────────────────────────────────────────────────┘

The bug the live run found, and the tests didn't

The two idempotent responses differed in production while the test passed:

original: 2026-09-27T20:57:02.347750677Z   ← built in memory, nanoseconds
replay:   2026-09-27T20:57:02.347751Z      ← read from Postgres, microseconds, rounded up

Postgres keeps microseconds and rounds, so the stored value isn't even a prefix of the original. My test clock sat on a whole second, so truncation never had anything to truncate — the test was structurally incapable of catching this.

Fixed in domain/EventTime: events are stamped at microsecond resolution, the resolution the log is actually kept at. Truncating there rather than in the Clock bean means it holds for every clock, including the fixed and mutable ones tests inject. The replay test now seeds a sub-microsecond instant, and I verified it goes red without the fix.

That also exposed clock state leaking between test classes through the cached Spring context — the nanosecond instant one class set was inherited by the next. IntegrationTest now resets the clock per test.

Judgement calls

- The transition response is the event, not the candidate. That's what makes "both responses match" true under concurrency rather than by luck.
- Replay returns 201, same as the original. A client cannot tell which call did the work — the point of idempotency.
- @RestControllerAdvice extends ResponseEntityExceptionHandler, so Spring's own failures (unparseable body, bad path variable, failed bean validation) come out of the same mould. Those are exactly the ones that normally escape a hand-rolled advice and break client parsing. Zero try/catch in controllers.
- OptimisticLockingFailureException and DataIntegrityViolationException both map to 409 — to a client they mean the same thing, and which one fires depends only on how tightly two requests overlap.
- JpaJobReader orders before limiting. My first version didn't, which meant "the single job" could resolve to a different row between calls. Caught while writing the fixtures.

Two things forced on me

- springdoc pinned to 2.7.0, not 2.8.17. The 2.8 line tracks Boot 3.5 and registers a swagger-ui resource pattern that Boot 3.4's PathPatternParser rejects outright — it took the entire application context down, not just Swagger. Commented at the dependency.
- CandidateSummary gained phone and source, and file 04's transition(...) call sites gained a null for expectedCurrentStage. Both were unavoidable consequences of this file's scope.

Open, and deliberate

- Bootstrap gap is real and now visible: with no job seeded, POST /candidates returns a clean 503 no-job-configured rather than a stack trace. File 06's seed closes it.
- Simultaneous requests sharing one idempotency key get 409, not the original. The loser's transaction is already rollback-only when the unique index fires. Its next retry gets the replay — asserted in ConcurrentTransitionTest. The fix is an outer non-transactional retry, which needs self-injection or a second bean; say the word.
- CurrentActor returns a placeholder. Every event is currently attributed to unauthenticated. One method to change when auth lands.
- expectedCurrentStage is required, as flagged.
  Ran 1 shell command -->

