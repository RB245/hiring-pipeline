# 03 - Domain: stage machine, transition rules, immutable decision objects

Prompt given: `prompts/03-*.md`

---

<!-- Before the plan — the seq question, since it changes the shape of the decision object.

Where seq comes from

It belongs to persistence, and I'd keep it out of the domain entirely.

seq is the ordinal position of a row in a stored log. The domain reasons about what happened — this candidate moved from Screening to Interview, and that's an ADVANCED event. Nothing in the four rules consults "how many events came before". The one place seq carries domain meaning is the constraint (seq = 1) = (from_stage IS NULL), and the domain already expresses that directly: the first event is the one with no origin stage. The number adds nothing it reasons with.

The alternative worth naming: hand the domain an event count on the candidate snapshot and let it return seq = count + 1. That stays deterministic and queries nothing, and it makes TransitionDecision self-contained. I'd still not do it — it puts a field in the domain that exists only because of how rows are stored, and it makes the domain responsible for a number it can't actually guarantee, since two concurrent transitions would both compute the same one.

Where it should be assigned: in the repository, inside the transaction, as max(seq) + 1 for that candidate. The race is real and already handled — stage_event_candidate_seq_uq from file 02 turns a concurrent double-assign into a 23505 rather than a corrupt log, and the version column on candidate makes the losing transaction fail optimistic locking first. So TransitionDecision carries the event without seq, and file 04 fills it in.

Plan

┌─────┬───────────────────────────────────────────────────┬──────────────────────────────────────────────┐
│  #  │                       Step                        │                 Verified by                  │
├─────┼───────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│     │ Write the three required tests against types that │ ./gradlew test — the new tests fail, the     │
│ 1   │  don't exist yet, plus stubs throwing             │ existing 22 stay green (a real red, not a    │
│     │ UnsupportedOperationException                     │ compile error)                               │
├─────┼───────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 2   │ Stage (successor, terminal, bit, entry event      │ compiles; 36-pair test moves past the stub   │
│     │ type) and EventType                               │                                              │
├─────┼───────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 3   │ TransitionRule sealed + AdvanceRule + RejectRule  │ all 36 pairs assert correctly — 8 allowed,   │
│     │ + TransitionRules registry                        │ 28 rejected                                  │
├─────┼───────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 4   │ IllegalStageTransitionException, StageEvent,      │ legal-alternatives test green                │
│     │ TransitionDecision, StageTransitions              │                                              │
├─────┼───────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 5   │ Candidate.timeInCurrentStage(Clock)               │ fixed-clock test green across midnight       │
├─────┼───────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│     │                                                   │ 3/3 still green — and I re-run the           │
│ 6   │ Drop allowEmptyShould(true) from the ArchUnit     │ deliberate-violation experiment to prove the │
│     │ rules                                             │  rules are now analysing real domain classes │
│     │                                                   │  rather than passing vacuously               │
├─────┼───────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 7   │ ./gradlew build                                   │ full suite green                             │
└─────┴───────────────────────────────────────────────────┴──────────────────────────────────────────────┘

What seems overcomplicated

The sealed interface plus registry is more structure than two predicates need. Once Stage knows its own successor, the entire rule set is: to == from.next(), or to == REJECTED && !from.isTerminal(). That's a six-line switch. The sealed hierarchy buys nothing today — nothing does an exhaustive switch over TransitionRule, so the sealing itself is inert.

It's justified by exactly one thing: the file-10 test that adding a stage means adding a constant and a rule and touching nothing else. A registry you append to satisfies that; a switch statement you edit does not. So I'm building it as you asked — I just want it on the record that its value is the extensibility property, not the current logic.

One small extension to your spec, for that same reason. You asked Stage to know its successor and whether it's terminal. I'm also giving it its bit value and the event type produced by entering it. The bit is needed for the reached-mask you asked the decision to carry, and it's declared explicitly rather than derived from ordinal() so that inserting a stage mid-pipeline can't silently invalidate every stored mask. The entry event type is what keeps AdvanceRule free of a to == HIRED ? HIRED : ADVANCED special case — with it on Stage, adding a terminal stage really does touch nothing else.

One gap I'm not filling: the initial APPLIED event, the one with no origin stage, isn't a transition and has no home in this file. Candidate creation lands in 04/05. Flagging it so it isn't a surprise.

Building test-first.

  Read 1 file

Step 1 — the three tests first.

● Write(backend\src\test\java\com\pipeline\domain\StageTransitionTableTest.java)
Wrote 76 lines to backend\src\test\java\com\pipeline\domain\StageTransitionTableTest.java
package com.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
… +68 lines

● Write(backend\src\test\java\com\pipeline\domain\StageTransitionsTest.java)
Wrote 89 lines to backend\src\test\java\com\pipeline\domain\StageTransitionsTest.java
package com.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
… +87 lines

● Write(backend\src\test\java\com\pipeline\domain\CandidateTest.java)
Wrote 50 lines to backend\src\test\java\com\pipeline\domain\CandidateTest.java
package com.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
… +40 lines

Now stubs, so the failure is a real red rather than a compile error.

  Ran 3 shell commands

ArchUnit picked up a failure the moment domain gained classes. Let me see which rule.

  Ran 1 shell command

Found a real bug in file 01's ArchUnit rule, not in the domain. ..api.. matches org.assertj.core.api — the rule was flagging AssertJ. It was invisible until domain had classes. Writing the implementations, then fixing that properly.

● Write(backend\src\main\java\com\pipeline\domain\Stage.java)
Added 48 lines, removed 5 lines

import java.util.Optional;

/**
 * The pipeline, in order. Declaration order is the pipeline order, and the successor of
 * a stage is simply the next one declared, so inserting a stage mid-pipeline rewires the
 * chain without touching any other constant.
 *
 * <p>The last stage declared must be terminal, or {@link #next()} would run off the end.
 */
public enum Stage {
    APPLIED, SCREENING, INTERVIEW, OFFER, HIRED, REJECTED;
    APPLIED(1, false, EventType.APPLIED),
    SCREENING(2, false, EventType.ADVANCED),
    INTERVIEW(4, false, EventType.ADVANCED),
    OFFER(8, false, EventType.ADVANCED),
    HIRED(16, true, EventType.HIRED),
    REJECTED(32, true, EventType.REJECTED);

    public Optional<Stage> next() { throw new UnsupportedOperationException(); }
    public boolean isTerminal() { throw new UnsupportedOperationException(); }
    public int bit() { throw new UnsupportedOperationException(); }
    public EventType entryEventType() { throw new UnsupportedOperationException(); }
    private final int bit;
    private final boolean terminal;
    private final EventType entryEventType;

    Stage(int bit, boolean terminal, EventType entryEventType) {
        this.bit = bit;
        this.terminal = terminal;
        this.entryEventType = entryEventType;
    }

    /** Empty for a terminal stage: there is nowhere to advance to. */
    public Optional<Stage> next() {
        return terminal ? Optional.empty() : Optional.of(values()[ordinal() + 1]);
    }

    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Position in the reached-mask. Declared rather than derived from the ordinal,
     * because deriving it would silently renumber every later stage — and so invalidate
     * every mask already stored — the first time someone inserts a stage mid-pipeline.
     */
    public int bit() {
        return bit;
    }

    /**
     * The kind of event recorded by entering this stage. Lives here rather than in the
     * rules so that no rule has to special-case a particular stage.
     */
    public EventType entryEventType() {
        return entryEventType;
    }
} 

Write(backend\src\main\java\com\pipeline\domain\AdvanceRule.java)
Added 9 lines, removed 1 line
package com.pipeline.domain;

/**
 * Exactly one stage forward. Skipping ahead, going back and standing still all fail the
 * same way, and a terminal stage has no successor to match against.
 */
public final class AdvanceRule implements TransitionRule {
    @Override public boolean allows(Stage from, Stage to) { throw new UnsupportedOperationExce
ption(); }

    @Override
    public boolean allows(Stage from, Stage to) {
        return from.next().filter(successor -> successor == to).isPresent();
    }
}

● Write(backend\src\main\java\com\pipeline\domain\RejectRule.java)
Added 9 lines, removed 1 line
package com.pipeline.domain;

/**
 * Rejection from anywhere still in play. Someone already hired cannot be rejected, and
 * someone already rejected cannot be rejected twice.
 */
public final class RejectRule implements TransitionRule {
    @Override public boolean allows(Stage from, Stage to) { throw new UnsupportedOperationExce
ption(); }

    @Override
    public boolean allows(Stage from, Stage to) {
        return to == Stage.REJECTED && !from.isTerminal();
    }
}

● Write(backend\src\main\java\com\pipeline\domain\TransitionRules.java)
Added 24 lines, removed 3 lines
package com.pipeline.domain;

import java.util.Arrays;
import java.util.List;

/**
 * The rule set, consulted as a whole. A transition is legal if any rule allows it, so a
 * new kind of move is a new rule added here rather than an edit to an existing one.
 */
public final class TransitionRules {
    public static TransitionRules standard() { throw new UnsupportedOperationException(); }
    public boolean isLegal(Stage from, Stage to) { throw new UnsupportedOperationException();
}
    public List<Stage> legalTargets(Stage from) { throw new UnsupportedOperationException(); }

    private final List<TransitionRule> rules;

    private TransitionRules(List<TransitionRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static TransitionRules standard() {
        return new TransitionRules(List.of(new AdvanceRule(), new RejectRule()));
    }

    public boolean isLegal(Stage from, Stage to) {
        return rules.stream().anyMatch(rule -> rule.allows(from, to));
    }

    /** In declaration order, so the alternatives offered to the recruiter are stable. */
    public List<Stage> legalTargets(Stage from) {
        return Arrays.stream(Stage.values()).filter(to -> isLegal(from, to)).toList();
    }
}

● Write(backend\src\main\java\com\pipeline\domain\IllegalStageTransitionException.java)
Added 40 lines, removed 4 lines
package com.pipeline.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Carries the alternatives that would have been accepted, because both the API and the
 * board need to tell the recruiter what she can do instead, not merely that she cannot
 * do this.
 *
 * <p>Unchecked: a caller attempting an illegal move has a bug or a stale screen, and
 * neither is something every call site should be forced to handle.
 */
public class IllegalStageTransitionException extends RuntimeException {
    public IllegalStageTransitionException(Stage from, Stage to, List<Stage> legalTargets) { s
uper("stub"); }
    public Stage from() { throw new UnsupportedOperationException(); }
    public Stage to() { throw new UnsupportedOperationException(); }
    public List<Stage> legalTargets() { throw new UnsupportedOperationException(); }

    private final transient Stage from;
    private final transient Stage to;
    private final transient List<Stage> legalTargets;

    public IllegalStageTransitionException(Stage from, Stage to, List<Stage> legalTargets) {
        super(describe(from, to, legalTargets));
        this.from = from;
        this.to = to;
        this.legalTargets = List.copyOf(legalTargets);
    }

    private static String describe(Stage from, Stage to, List<Stage> legalTargets) {
        if (legalTargets.isEmpty()) {
            return "Cannot move from %s to %s: %s is terminal".formatted(from, to, from);
        }
        return "Cannot move from %s to %s; legal moves from %s are %s"
                .formatted(from, to, from, legalTargets.stream().map(Enum::name).collect(Colle
ctors.joining(", ")));
    }

    public Stage from() {
        return from;
    }

    public Stage to() {
        return to;
    }

    public List<Stage> legalTargets() {
        return legalTargets;
    }
}

● Write(backend\src\main\java\com\pipeline\domain\StageTransitions.java)
Added 35 lines, removed 4 lines

import java.time.Clock;

/**
 * Applies the rules and says what should be recorded. It decides; it does not store, and
 * it does not know that storing is a thing that happens.
 */
public final class StageTransitions {
    public StageTransitions(TransitionRules rules, Clock clock) {}
    public TransitionDecision transition(Candidate candidate, Stage target, Actor actor,
                                         String reason, String idempotencyKey) {
        throw new UnsupportedOperationException();

    private final TransitionRules rules;
    private final Clock clock;

    public StageTransitions(TransitionRules rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * @throws IllegalStageTransitionException if the move is not permitted, carrying the
     *     moves that would have been
     */
    public TransitionDecision transition(
            Candidate candidate, Stage target, Actor actor, String reason, String idempotencyK
ey) {
        Stage from = candidate.currentStage();
        if (!rules.isLegal(from, target)) {
            throw new IllegalStageTransitionException(from, target, rules.legalTargets(from));
        }

        StageEvent event = new StageEvent(
                candidate.id(),
                from,
                target,
                target.entryEventType(),
                clock.instant(),
                actor,
                reason,
                idempotencyKey);

        return new TransitionDecision(target, event, candidate.reachedMask() | target.bit());
    }
}

 Write(backend\src\main\java\com\pipeline\domain\Candidate.java)
Added 12 lines, removed 1 line
import java.time.Instant;
import java.util.UUID;

/**
 * What the domain needs to know about a candidate to decide their next move. Only the
 * projection, not the history: the rules never consult earlier events.
 */
public record Candidate(UUID id, Stage currentStage, Instant currentStageSince, int reachedMas
k) {
    public Duration timeInCurrentStage(Clock clock) { throw new UnsupportedOperationException(
); }

    /**
     * Clock arrives as an argument rather than a field because this is a value, not a
     * service. Elapsed time, deliberately, not a difference between calendar dates.
     */
    public Duration timeInCurrentStage(Clock clock) {
        return Duration.between(currentStageSince, clock.instant());
    }
}

● Write(backend\src\main\java\com\pipeline\domain\StageEvent.java)
Added 14 lines, removed 2 lines
import java.time.Instant;
import java.util.UUID;

public record StageEvent(UUID candidateId, Stage fromStage, Stage toStage, EventType eventType
,
                         Instant occurredAt, Actor actor, String reason, String idempotencyKey
) {}
/**
 * The event an accepted transition produces. No {@code seq}: the position of this event
 * in the stored log is a property of the log, assigned when it is written, and nothing
 * in the domain reasons about it.
 */
public record StageEvent(
        UUID candidateId,
        Stage fromStage,
        Stage toStage,
        EventType eventType,
        Instant occurredAt,
        Actor actor,
        String reason,
        String idempotencyKey) {}

● Write(backend\src\main\java\com\pipeline\domain\TransitionDecision.java)
Added 5 lines
package com.pipeline.domain;

/**
 * Everything the persistence layer needs and nothing about how to persist it. The new
 * {@code current_stage_since} is the event's own {@code occurredAt}, so it is not
 * repeated here.
 */
public record TransitionDecision(Stage newStage, StageEvent event, int reachedMask) {}

Now the ArchUnit fix — two separate problems in that rule.

● Write(backend\src\test\java\com\pipeline\ArchitectureTest.java)
Added 13 lines, removed 10 lines

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.pipeline")
/**
 * Production code only. A test for a domain class is entitled to use JUnit and AssertJ;
 * the boundary being defended is the one the shipped application crosses.
 */
@AnalyzeClasses(packages = "com.pipeline", importOptions = ImportOption.DoNotIncludeTests.clas
s)
class ArchitectureTest {

    // allowEmptyShould is on because the domain package has no classes yet; the rules
    // start biting as soon as it does.

    @ArchTest
    static final ArchRule domainIsFreeOfSpring = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .allowEmptyShould(true);
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule domainIsFreeOfJpa = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
            .allowEmptyShould(true);
            .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..");

    // Fully qualified, not "..api..": that form matches any package with an "api"
    // segment anywhere, including org.assertj.core.api, and would fail on libraries
    // that have nothing to do with this application's layering.
    @ArchTest
    static final ArchRule domainDependsOnNoOuterLayer = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..api.."
)
            .allowEmptyShould(true);
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.pipeline.infrastructure..", "com.pipeline.api..");
}

  Ran 1 shell command

All 67 green. Now proving the ArchUnit rules are no longer vacuous, with allowEmptyShould removed:

  Ran 2 shell commands

Done.

Verification results

┌────────────────────────┬───────────────────────────────────────────────────────────────────────────────┐
│         Check          │                                    Result                                     │
├────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ Test-first             │ ✅ genuine red first: 45 new tests failing against                            │
│                        │ UnsupportedOperationException stubs, existing 22 still green                  │
├────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ All 36 stage pairs,    │ ✅ 8 allowed, 28 rejected — plus a 37th test asserting the table is still 36  │
│ exhaustive             │ pairs and 8 legal, so a shrinking parameter source can't pass quietly         │
├────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ Typed error carries    │ ✅ APPLIED → INTERVIEW yields [SCREENING, REJECTED]; terminal stages yield [] │
│ legal alternatives     │  and say "terminal"                                                           │
├────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ Fixed clock across a   │ ✅ 22:45 → 02:15 is 3h30m; 23:59 → 00:01 is 2 minutes and toDays() is 0       │
│ day boundary           │                                                                               │
├────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ ArchUnit still passes  │ ✅ 3/3 — and re-verified by putting @Component, @Entity and an infrastructure │
│                        │  reference on a real domain class, watching all three go red, then reverting  │
├────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ ./gradlew build        │ ✅ 67 tests green                                                             │
├────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ Domain imports         │ ✅ java.time, java.util only — nothing else                                   │
└────────────────────────┴───────────────────────────────────────────────────────────────────────────────┘

One thing I had to fix that wasn't in scope

File 01's ArchUnit rule had a latent bug that only surfaced today. resideInAnyPackage("..api..") matches any package with an api segment — including org.assertj.core.api. The moment domain gained test classes that assert on things, the rule reported 68 violations, all of them AssertJ and none of them real.

Two changes, both in ArchitectureTest.java:

- ..api.. / ..infrastructure.. → com.pipeline.api.. / com.pipeline.infrastructure... The rule always meant our packages; the loose form would also have fired on any dependency with an api package in production code.
- Added ImportOption.DoNotIncludeTests. A test for a domain class is entitled to use JUnit and AssertJ — the boundary worth defending is the one the shipped jar crosses.

allowEmptyShould(true) is gone, as you asked, and the rules are demonstrably not vacuous now.

Design decisions worth your attention

seq is absent from StageEvent. As argued above — it's assigned by the repository inside the transaction in file 04, and stage_event_candidate_seq_uq plus the version column already make the concurrent case safe.

current_stage_since is absent from TransitionDecision. It's exactly event.occurredAt(), and carrying it twice invites the two copies to disagree. The decision is the three things you specified: new stage, event, reached-mask.

Clock is a method parameter on Candidate, not a field. Candidate is a value; injecting a clock into a record's constructor would make equality depend on it. StageTransitions takes it constructor-injected, which is where the Spring bean from file 01 will be wired in file 04. No Instant.now() anywhere.

The transient keyword on the exception's fields is there because RuntimeException is serializable and Stage/List fields would otherwise put a serialization contract on the domain that nobody wants. The accessors are unaffected.

Still open

- The initial APPLIED event has no home. It isn't a transition — no origin stage — so nothing in this file produces it. Candidate creation needs a separate entry point in 04/05, and that's the one place from_stage IS NULL and seq = 1 get written together.
- TransitionRules exposes only standard(). If file 10's extensibility test wants to construct a registry with a custom rule list, that's a one-line public constructor — I left it out rather than add an entry point nothing calls.
- The sealed interface is still inert, as flagged before building: nothing switches exhaustively over TransitionRule. It earns its keep only under the file-10 test. If that test ends up not caring, this collapses to a six-line switch and three files disappear.
-->