package com.pipeline.domain;

import java.time.Clock;

/**
 * Applies the rules and says what should be recorded. It decides; it does not store, and
 * it does not know that storing is a thing that happens.
 */
public final class StageTransitions {

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
            Candidate candidate, Stage target, Actor actor, String reason, String idempotencyKey) {
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
