package com.pipeline.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * What the domain needs to know about a candidate to decide their next move. Only the
 * projection, not the history: the rules never consult earlier events.
 */
public record Candidate(UUID id, Stage currentStage, Instant currentStageSince, int reachedMask) {

    /**
     * Clock arrives as an argument rather than a field because this is a value, not a
     * service. Elapsed time, deliberately, not a difference between calendar dates.
     */
    public Duration timeInCurrentStage(Clock clock) {
        return Duration.between(currentStageSince, clock.instant());
    }
}
