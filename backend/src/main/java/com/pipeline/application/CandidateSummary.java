package com.pipeline.application;

import com.pipeline.domain.Stage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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

    /** Computed on read from the injected clock, never stored. */
    public Duration timeInCurrentStage(Clock clock) {
        return Duration.between(currentStageSince, clock.instant());
    }
}
