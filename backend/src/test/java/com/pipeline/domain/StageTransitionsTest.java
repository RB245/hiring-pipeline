package com.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StageTransitionsTest {

    private static final Instant NOW = Instant.parse("2025-03-11T09:30:00Z");
    private static final Instant SINCE = Instant.parse("2025-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Actor ACTOR = new Actor("recruiter-1", "Asha");

    private final StageTransitions transitions = new StageTransitions(TransitionRules.standard(), CLOCK);

    @Test
    void theErrorCarriesTheAlternativesThatWouldHaveWorked() {
        Candidate candidate = new Candidate(UUID.randomUUID(), Stage.APPLIED, SINCE, Stage.APPLIED.bit());

        IllegalStageTransitionException thrown = catchThrowableOfType(
                IllegalStageTransitionException.class,
                () -> transitions.transition(candidate, Stage.INTERVIEW, ACTOR, null, null));

        assertThat(thrown.from()).isEqualTo(Stage.APPLIED);
        assertThat(thrown.to()).isEqualTo(Stage.INTERVIEW);
        assertThat(thrown.legalTargets()).containsExactly(Stage.SCREENING, Stage.REJECTED);
        assertThat(thrown).hasMessageContaining("SCREENING").hasMessageContaining("REJECTED");
    }

    @Test
    void aTerminalStageOffersNoAlternatives() {
        Candidate candidate = new Candidate(UUID.randomUUID(), Stage.HIRED, SINCE, Stage.HIRED.bit());

        IllegalStageTransitionException thrown = catchThrowableOfType(
                IllegalStageTransitionException.class,
                () -> transitions.transition(candidate, Stage.REJECTED, ACTOR, null, null));

        assertThat(thrown.legalTargets()).isEmpty();
        assertThat(thrown).hasMessageContaining("terminal");
    }

    @Test
    void anAcceptedTransitionDescribesExactlyWhatToPersist() {
        UUID candidateId = UUID.randomUUID();
        int maskSoFar = Stage.APPLIED.bit() | Stage.SCREENING.bit();
        Candidate candidate = new Candidate(candidateId, Stage.SCREENING, SINCE, maskSoFar);

        TransitionDecision decision =
                transitions.transition(candidate, Stage.INTERVIEW, ACTOR, "strong take-home", "key-1");

        assertThat(decision.newStage()).isEqualTo(Stage.INTERVIEW);
        assertThat(decision.reachedMask()).isEqualTo(maskSoFar | Stage.INTERVIEW.bit());

        StageEvent event = decision.event();
        assertThat(event.candidateId()).isEqualTo(candidateId);
        assertThat(event.fromStage()).isEqualTo(Stage.SCREENING);
        assertThat(event.toStage()).isEqualTo(Stage.INTERVIEW);
        assertThat(event.eventType()).isEqualTo(EventType.ADVANCED);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.actor()).isEqualTo(ACTOR);
        assertThat(event.reason()).isEqualTo("strong take-home");
        assertThat(event.idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    void reachingAStageTwiceDoesNotDisturbTheMask() {
        int maskSoFar = Stage.APPLIED.bit() | Stage.SCREENING.bit() | Stage.INTERVIEW.bit();
        Candidate candidate = new Candidate(UUID.randomUUID(), Stage.INTERVIEW, SINCE, maskSoFar);

        TransitionDecision decision = transitions.transition(candidate, Stage.OFFER, ACTOR, null, null);

        assertThat(decision.reachedMask()).isEqualTo(maskSoFar | Stage.OFFER.bit());
    }

    @Test
    void enteringATerminalStageIsTypedAsThatStageNotAsAnAdvance() {
        Candidate offered = new Candidate(UUID.randomUUID(), Stage.OFFER, SINCE, Stage.OFFER.bit());

        assertThat(transitions.transition(offered, Stage.HIRED, ACTOR, null, null).event().eventType())
                .isEqualTo(EventType.HIRED);
        assertThat(transitions.transition(offered, Stage.REJECTED, ACTOR, null, null).event().eventType())
                .isEqualTo(EventType.REJECTED);
    }
}
