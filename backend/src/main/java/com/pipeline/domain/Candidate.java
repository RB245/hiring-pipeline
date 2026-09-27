package com.pipeline.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the domain needs to know about a candidate to decide their next move. Only the
 * projection, not the history: the rules never consult earlier events.
 */
public record Candidate(UUID id, Stage currentStage, Instant currentStageSince, int reachedMask) {

    /**
     * Entering the pipeline. Not a transition — there is no stage to come from — so this
     * is the one place an event is produced without a rule being consulted, and the one
     * place a {@code from_stage} of null is correct.
     */
    public static CandidateCreation register(UUID id, Actor actor, Clock clock) {
        Instant now = clock.instant();
        Candidate candidate = new Candidate(id, Stage.APPLIED, now, Stage.APPLIED.bit());
        StageEvent firstEvent = new StageEvent(
                id, null, Stage.APPLIED, Stage.APPLIED.entryEventType(), now, actor, null, null);
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
            throw new IllegalArgumentException("Candidate " + id + " has no events to replay");
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
    public Duration timeInCurrentStage(Clock clock) {
        return Duration.between(currentStageSince, clock.instant());
    }
}
