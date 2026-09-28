# 04 - Persistence: split ports, JPA adapters, atomic write path

Prompt given: `prompts/04-*.md`

---

<!-- Two decisions to settle before the plan, plus one thing I found already exists.

seq — settled in file 03 and unchanged: the adapter assigns it as max(seq) + 1 inside the transaction. Optimistic locking on candidate.version makes the loser of a race fail before it can commit, and stage_event_candidate_seq_uq is the backstop if it somehow doesn't. version is the same kind of thing — it stays on the JPA entity and never reaches the domain, for exactly the reason seq doesn't.

Your requested test "the V4 check constraint rejects a second event with from_stage IS NULL" already exists — SchemaConstraintTest.laterEventMustDeclareWhereItCameFrom, written in file 02, inserts seq=2, from_stage=NULL and asserts 23514. I won't duplicate it. The version that is new at this layer, and that I will write, is that registering the same candidate twice can't produce a second originless event — same constraint, reached through the write path rather than through raw SQL.

Plan

┌─────┬──────────────────────────────────────────────┬───────────────────────────────────────────────────┐
│  #  │                     Step                     │                    Verified by                    │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│     │                                              │ a test defining a new rule outside the domain     │
│ 1   │ Unseal TransitionRule; add                   │ package composes into the registry and shows up   │
│     │ TransitionRules(List<TransitionRule>)        │ in legalTargets — no edit to                      │
│     │                                              │ AdvanceRule/RejectRule                            │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│     │ Domain: Candidate.register(id, actor, clock) │ unit test: first event has from == null, type     │
│ 2   │  → CandidateCreation                         │ APPLIED, mask = APPLIED bit, occurredAt from the  │
│     │                                              │ fixed clock                                       │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 3   │ Domain: Candidate.replay(id, events)         │ unit test over a 4-event history, no database     │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 4   │ Ports in application/: CandidateReader,      │ compiles; ArchUnit unchanged                      │
│     │ CandidateWriter, EventReader + read models   │                                                   │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 5   │ infrastructure/: JPA entities, Spring Data   │ context boots as pipeline_app; happy-path         │
│     │ repos, three adapters                        │ transition writes 1 event and 3 columns           │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│     │ Use cases: RegisterCandidate,                │                                                   │
│ 6   │ TransitionCandidate,                         │ happy-path tests                                  │
│     │ RebuildCandidateProjection                   │                                                   │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 7   │ Rollback: force step 4 to throw; force the   │ zero orphan rows in both directions               │
│     │ first-event append to throw                  │                                                   │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 8   │ Registering twice can't write a second       │ 23514 from stage_event_first_has_no_from          │
│     │ originless event                             │                                                   │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 9   │ Board, timeline, keyset page                 │ pagination test inserts rows between page 1 and   │
│     │                                              │ page 2 and asserts no repeats and no skips        │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 10  │ Rebuild test: seed events, corrupt the       │ all three columns match what the log implies      │
│     │ projection by raw SQL, rebuild               │                                                   │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 11  │ ArchUnit: add application must not depend on │ deliberately violate it, watch it go red, revert  │
│     │  infrastructure                              │                                                   │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 12  │ ./gradlew build + docker compose up smoke    │ full suite green, /actuator/health UP             │
└─────┴──────────────────────────────────────────────┴───────────────────────────────────────────────────┘

The honest README sentence

▎ A new kind of move costs one class: implement TransitionRule, add it to the list in TransitionRules, and no existing rule changes. A new stage costs more than that — a constant in Stage with its own mask bit, an ALTER TYPE ... ADD VALUE migration, at least one rule that can reach it, and a column in the board UI.

Step 1's test proves the first sentence. Nothing tests the second, because it isn't a property, it's a checklist.

Things I'd do differently, before I start

"A count per stage" — I'm not storing one. If BoardColumn carries both the candidate list and a separate count, they're two sources of one truth and can disagree. I'll expose count() as a derived accessor over the list. You get the number; there's nothing to drift.

Keyset pagination has no supporting index and I'm not adding one. Ordering is (created_at DESC, id DESC); at 200 rows that's a sort over a tiny set. Adding candidate_created_at_id_idx would break the rule from file 02 that every index names a recruiter question. Say the word and it's a four-line V8.

The cursor stays a value object, not a string. Cursor(Instant, UUID). Base64-encoding it so it's opaque to clients is an HTTP concern and belongs in file 05.

One interface I'm not creating. You said "expose it as a port" for the rebuild. The outbound three are real ports — they invert a dependency. A one-implementation interface in front of a use case the controller calls directly is ceremony, so RebuildCandidateProjection will be a concrete class with a public method. Tell me if you meant the interface literally and it's a two-minute change.

application/ will import Spring (@Service, @Transactional). Only domain/ is required to be framework-free. Flagging it because it's the layer where people disagree.

Building.
 Read 1 file

● Write(backend\src\main\java\com\pipeline\domain\TransitionRule.java)
Added 6 lines, removed 1 line
package com.pipeline.domain;

public sealed interface TransitionRule permits AdvanceRule, RejectRule {
/**
 * One kind of legal move. Deliberately not sealed: {@link TransitionRules} composes
 * rules from a list so that a new kind of move is a new rule rather than an edit to an
 * existing one, and sealing would have made that impossible outside this one file.
 */
public interface TransitionRule {
    boolean allows(Stage from, Stage to);
}

● Update(backend\src\main\java\com\pipeline\domain\TransitionRules.java)
Added 1 line, removed 1 line

    private final List<TransitionRule> rules;

    private TransitionRules(List<TransitionRule> rules) {
    public TransitionRules(List<TransitionRule> rules) {
        this.rules = List.copyOf(rules);
    }


Now the domain additions — creation and replay.

● Write(backend\src\main\java\com\pipeline\domain\CandidateCreation.java)
Wrote 8 lines to backend\src\main\java\com\pipeline\domain\CandidateCreation.java
package com.pipeline.domain;

/**
 * What registering a candidate produces. The counterpart to {@link TransitionDecision}:
 * a candidate does not transition into existence, so the first event has no origin
 * stage and no rule governs it.
 */
public record CandidateCreation(Candidate candidate, StageEvent firstEvent) {}

● Write(backend\src\main\java\com\pipeline\domain\Candidate.java)
Added 32 lines
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**

public record Candidate(UUID id, Stage currentStage, Instant currentStageSince, int reachedMas
k) {

    /**
     * Entering the pipeline. Not a transition — there is no stage to come from — so this
     * is the one place an event is produced without a rule being consulted, and the one
     * place a {@code from_stage} of null is correct.
     */
    public static CandidateCreation register(UUID id, Actor actor, Clock clock) {
        Instant now = clock.instant();
        Candidate candidate = new Candidate(id, Stage.APPLIED, now, Stage.APPLIED.bit());
        StageEvent firstEvent = new StageEvent(
                id, null, Stage.APPLIED, Stage.APPLIED.entryEventType(), now, actor, null, nul
l);
        return new CandidateCreation(candidate, firstEvent);
    }

    /**
     * Rebuilds the projection from the log, which is the only thing that makes the
     * projection safe to denormalise. Events must already be in the order they were
     * recorded; nothing here can re-derive that order, because the domain has no notion
     * of the sequence number the log is keyed by.
     */
    public static Candidate replay(UUID id, List<StageEvent> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Candidate " + id + " has no events to replay")
;
        }
        int reachedMask = 0;
        for (StageEvent event : events) {
            reachedMask |= event.toStage().bit();
        }
        StageEvent last = events.get(events.size() - 1);
        return new Candidate(id, last.toStage(), last.occurredAt(), reachedMask);
    }

    /**
     * Clock arrives as an argument rather than a field because this is a value, not a
     * service. Elapsed time, deliberately, not a difference between calendar dates.
     */

Now the domain tests for these three changes.

● Write(backend\src\test\java\com\pipeline\domain\CandidateRegistrationTest.java)
Wrote 62 lines to backend\src\test\java\com\pipeline\domain\CandidateRegistrationTest.java
package com.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
… +55 lines

● Write(backend\src\test\java\com\pipeline\rules\CustomTransitionRuleTest.java)
Wrote 75 lines to backend\src\test\java\com\pipeline\rules\CustomTransitionRuleTest.java
package com.pipeline.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.domain.Actor;
import com.pipeline.domain.AdvanceRule;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.EventType;
import com.pipeline.domain.RejectRule;
import com.pipeline.domain.Stage;
… +67 lines

  Ran 1 shell command

Carry-overs done (74 green). Now the application ports and read models.

  Ran 1 shell command

Now the three use cases.

Write(backend\src\main\java\com\pipeline\application\RegisterCandidate.java)
Wrote 33 lines to backend\src\main\java\com\pipeline\application\RegisterCandidate.java
package com.pipeline.application;

import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.CandidateCreation;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
… +23 lines

● Write(backend\src\main\java\com\pipeline\application\TransitionCandidate.java)
Wrote 52 lines to backend\src\main\java\com\pipeline\application\TransitionCandidate.java
package com.pipeline.application;

import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageTransitions;
import com.pipeline.domain.TransitionDecision;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
… +46 lines

● Write(backend\src\main\java\com\pipeline\application\RebuildCandidateProjection.java)
Wrote 38 lines to backend\src\main\java\com\pipeline\application\RebuildCandidateProjection.java
package com.pipeline.application;

import com.pipeline.domain.Candidate;
import com.pipeline.domain.StageEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
… +29 lines

Now the infrastructure adapters.

  Ran 2 shell commands

● Write(backend\src\main\java\com\pipeline\infrastructure\JpaCandidateWriter.java)
Wrote 74 lines to backend\src\main\java\com\pipeline\infrastructure\JpaCandidateWriter.java
package com.pipeline.infrastructure;

import com.pipeline.application.CandidateNotFoundException;
import com.pipeline.application.CandidateProfile;
import com.pipeline.application.CandidateWriter;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageEvent;
import java.time.Instant;
import java.util.UUID;
… +65 lines

● Write(backend\src\main\java\com\pipeline\infrastructure\JpaCandidateReader.java)
Wrote 82 lines to backend\src\main\java\com\pipeline\infrastructure\JpaCandidateReader.java
package com.pipeline.infrastructure;

import com.pipeline.application.BoardColumn;
import com.pipeline.application.CandidatePage;
import com.pipeline.application.CandidateReader;
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.Cursor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import java.util.ArrayList;
… +74 lines

  Ran 1 shell command

Now the test harness. First, making file 02's container reusable rather than starting a second one.

  Read 1 file

● Update(backend\src\test\java\com\pipeline\db\SchemaFixture.java)
Added 2 lines, removed 2 lines
 * rather than H2 because enums, partial indexes, generated columns, triggers and role
 * grants are the things under test and H2 would wave most of them through.
 */
final class SchemaFixture {
public final class SchemaFixture {

    static final String APP_PASSWORD = "app-password";
    public static final String APP_PASSWORD = "app-password";
    static final OffsetDateTime T0 = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
;

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres
:16");

● Update(backend\src\test\java\com\pipeline\db\SchemaFixture.java)
Added 6 lines, removed 1 line
                .migrate();
    }

    /** Shared with the persistence tests so the whole run needs only one container. */
    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /** The identity Flyway ran as: a member of pipeline_migrator, so it holds DDL. */
    static Connection asOwner() throws SQLException {
    public static Connection asOwner() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

● Write(backend\src\test\java\com\pipeline\persistence\PersistenceTest.java)
Wrote 81 lines to backend\src\test\java\com\pipeline\persistence\PersistenceTest.java
package com.pipeline.persistence;

import com.pipeline.db.SchemaFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
… +73 lines

● Write(backend\src\test\java\com\pipeline\persistence\MutableClock.java)
Wrote 43 lines to backend\src\test\java\com\pipeline\persistence\MutableClock.java
package com.pipeline.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test drives. Needed because several of these tests turn on the order and
… +33 lines

Now the write-path tests: happy path, both rollbacks, optimistic locking, and the double-registration constraint.

● Write(backend\src\test\java\com\pipeline\persistence\WritePathTest.java)
Wrote 182 lines to backend\src\test\java\com\pipeline\persistence\WritePathTest.java
package com.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import com.pipeline.application.CandidateProfile;
… +178 lines

● Write(backend\src\test\java\com\pipeline\persistence\ProjectionRebuildTest.java)
Wrote 97 lines to backend\src\test\java\com\pipeline\persistence\ProjectionRebuildTest.java
package com.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.application.CandidateProfile;
import com.pipeline.application.CandidateReader;
import com.pipeline.application.RebuildCandidateProjection;
import com.pipeline.application.RegisterCandidate;
import com.pipeline.application.TransitionCandidate;
import com.pipeline.db.SchemaFixture;
… +90 lines

● Write(backend\src\test\java\com\pipeline\persistence\ReadPathTest.java)
Wrote 144 lines to backend\src\test\java\com\pipeline\persistence\ReadPathTest.java
package com.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.application.BoardColumn;
import com.pipeline.application.CandidatePage;
import com.pipeline.application.CandidateProfile;
import com.pipeline.application.CandidateReader;
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.EventReader;
… +140 lines

  Ran 2 shell commands

Schema validation rejects citext vs varchar. Let me pin the column types explicitly — the enum columns will hit the same thing (event_type won't match Hibernate's derived name).

  Ran 2 shell commands

87 of 88. The remaining failure is my own malformed assertion — let me see what actually gets thrown.

  Ran 1 shell command

● Update(backend\src\test\java\com\pipeline\persistence\WritePathTest.java)
Removed 1 line
        UUID id = register();

        assertThatThrownBy(() -> writeSecondOriginlessEvent(id))
                .hasRootCauseMessage(null)
                .rootCause()
                .hasMessageContaining("stage_event_first_has_no_from");


  Ran 1 shell command

88 green. Now the ArchUnit rule keeping the dependency pointing inward.

● Update(backend\src\test\java\com\pipeline\ArchitectureTest.java)
Added 9 lines
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

  Ran 1 shell command

Final check — full build plus a real compose run to confirm the entity mappings validate against the live schema.

  Ran 2 shell commands

Done.

Verification results — all executed

Check: Transition writes exactly one event and all three projection columns atomically
Result: ✅ WritePathTest.aTransitionWritesExactlyOneEventAndAllThreeProjectionColumns
────────────────────────────────────────
Check: Rollback test proves no orphaned events
Result: ✅ step 4 forced to throw → still 1 event, stage unchanged
────────────────────────────────────────
Check: Failed candidate creation leaves no event and no candidate
Result: ✅ the candidate row is saveAndFlush-ed before the event, so only the rollback can undo it — that's
what makes the assertion mean something
────────────────────────────────────────
Check: Rebuild reconstructs the projection from the log
Result: ✅ corrupted all three columns by raw SQL as the owner, rebuilt, all three match
────────────────────────────────────────
Check: Keyset pagination stable across inserts
Result: ✅ two rows inserted between page 1 and page 2; page 2 is exactly [c3, c2], no repeats, no skips
────────────────────────────────────────
Check: Registering twice can't write a second originless event
Result: ✅ stage_event_first_has_no_from
────────────────────────────────────────
Check: Optimistic locking
Result: ✅ concurrent version bump → OptimisticLockingFailureException, projection unchanged
────────────────────────────────────────
Check: Tests run as the restricted role
Result: ✅ pipeline_app, not the owner
────────────────────────────────────────
Check: Real Postgres
Result: ✅ one shared postgres:16 container for the whole run
────────────────────────────────────────
Check: ArchUnit
Result: ✅ 4 rules, including the new application ⇸ infrastructure — verified red by deliberate violation,
then reverted
────────────────────────────────────────
Check: ./gradlew build
Result: ✅ 88 tests
────────────────────────────────────────
Check: docker compose up from empty
Result: ✅ 7 migrations, Started PipelineApplication, health UP, no schema-validation errors

Carry-overs

TransitionRule is unsealed and TransitionRules has a public list constructor. CustomTransitionRuleTest declares a FastTrackRule in package com.pipeline.rules — a different package entirely, which sealing forbade — and proves it composes in, changes isLegal, appears in legalTargets in declaration order, and produces the right event type without any rule knowing about event types. No edit to AdvanceRule or RejectRule.

Candidate creation is Candidate.register(id, actor, clock) in the domain, returning CandidateCreation(candidate, firstEvent). It's the counterpart to TransitionDecision and the one place from_stage == null is produced. created_at on the row is set to the first event's occurredAt, so the row and its log can't disagree about when the candidate appeared.

Your requested test already existed. "The V4 check constraint rejects a second event with from_stage IS NULL" is SchemaConstraintTest.laterEventMustDeclareWhereItCameFrom from file 02. I didn't duplicate it; I wrote the write-path version instead.

The honest README sentence

▎ A new kind of move costs one class: implement TransitionRule, add it to the list in TransitionRules, and no existing rule changes. A new stage costs more — a constant in Stage with its own mask bit, an ALTER TYPE ... ADD VALUE migration, at least one rule that can reach it, and a column in the board UI.

The first sentence is tested. The second isn't, because it's a checklist, not a property.

Two things that fought back

ddl-auto: validate rejected the schema on first run. citext isn't varchar, and Hibernate derives eventtype from the Java enum name while file 02 created event_type. Fixed with explicit columnDefinition on four columns (citext, stage ×3, event_type). Hibernate's validator accepts a column whose declared SQL type name matches the database's, which is why this works rather than being a fudge.

stage_event.id is IDENTITY-generated, so persist executes its INSERT immediately — before Hibernate would otherwise have flushed the candidate row it references. insertCandidate therefore uses saveAndFlush, commented in place. Without it, registration would fail on the FK.

Judgement calls you should know about

- BoardColumn.count() is derived, not stored. You asked for a count per stage; you have one, with nothing that can drift from the list it counts.
- No index for keyset pagination. Ordering is (created_at DESC, id DESC) with no supporting index, because adding one would break file 02's rule that every index names a recruiter question. Four-line V8 whenever you want it.
- RebuildCandidateProjection is a concrete class, not an interface. The three outbound ports are real ports; a one-implementation interface in front of a use case is ceremony. Two-minute change if you meant it literally.
- version never reaches the domain, for the same reason seq doesn't — it describes the row, not the candidate. The load inside the transaction arms it; the entity stays managed so the flush checks the version read at load time.
- SchemaFixture went from package-private to public (plus a jdbcUrl() accessor) so the persistence tests share one container instead of starting a second Postgres. That's the only file-02 test code I touched.
- application/ imports Spring (@Service, @Transactional). Only domain/ is framework-free, and ArchUnit now enforces that application can't reach its own adapters either.

Still open from earlier: pipeline_app has SELECT only on job — the persistence tests create job rows as the owner. File 06's seeder will need either the same treatment or a one-line grant.
 -->

