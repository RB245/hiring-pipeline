package com.pipeline.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.domain.Actor;
import com.pipeline.domain.AdvanceRule;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.EventType;
import com.pipeline.domain.RejectRule;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageTransitions;
import com.pipeline.domain.TransitionDecision;
import com.pipeline.domain.TransitionRule;
import com.pipeline.domain.TransitionRules;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The claim worth testing: a new kind of move costs one class and no edit to any
 * existing rule. This rule is declared in a different package entirely, which is what
 * sealing used to forbid.
 *
 * <p>The claim deliberately not tested here is "adding a stage means an enum constant
 * and a rule". A stage also needs a mask bit, an ALTER TYPE migration and a board
 * column, so it is a checklist rather than a property.
 */
class CustomTransitionRuleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-03-11T09:30:00Z"), ZoneOffset.UTC);
    private static final Actor ACTOR = new Actor("recruiter-1", "Asha");

    /** A move the standard rules do not permit: skipping screening for a known quantity. */
    private static final class FastTrackRule implements TransitionRule {
        @Override
        public boolean allows(Stage from, Stage to) {
            return from == Stage.APPLIED && to == Stage.INTERVIEW;
        }
    }

    private final TransitionRules extended =
            new TransitionRules(List.of(new AdvanceRule(), new RejectRule(), new FastTrackRule()));

    @Test
    void theStandardRulesStillRejectTheMove() {
        assertThat(TransitionRules.standard().isLegal(Stage.APPLIED, Stage.INTERVIEW)).isFalse();
    }

    @Test
    void addingARuleAddsTheMove() {
        assertThat(extended.isLegal(Stage.APPLIED, Stage.INTERVIEW)).isTrue();
    }

    /** legalTargets is derived from the rules, so the offered alternatives cannot drift. */
    @Test
    void theNewMoveAppearsAmongTheLegalAlternatives() {
        assertThat(extended.legalTargets(Stage.APPLIED))
                .containsExactly(Stage.SCREENING, Stage.INTERVIEW, Stage.REJECTED);
    }

    @Test
    void theNewMoveProducesAnEventWithoutAnyRuleKnowingAboutEventTypes() {
        Candidate candidate = new Candidate(
                UUID.randomUUID(), Stage.APPLIED, Instant.parse("2025-03-01T00:00:00Z"), Stage.APPLIED.bit());

        TransitionDecision decision = new StageTransitions(extended, CLOCK)
                .transition(candidate, Stage.INTERVIEW, ACTOR, "knows the team", null);

        assertThat(decision.event().eventType()).isEqualTo(EventType.ADVANCED);
        assertThat(decision.reachedMask()).isEqualTo(Stage.APPLIED.bit() | Stage.INTERVIEW.bit());
    }
}
