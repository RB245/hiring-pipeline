package com.pipeline.api;

import com.pipeline.application.CandidateSummary;
import com.pipeline.domain.Stage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record CandidateResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        String source,
        Stage currentStage,
        Instant currentStageSince,
        @Schema(description = "ISO-8601 duration", example = "PT144H") String timeInCurrentStage,
        @Schema(example = "6 days") String timeInCurrentStageHumanised,
        Instant createdAt) {

    static CandidateResponse of(CandidateSummary summary, Clock clock) {
        Duration inStage = summary.timeInCurrentStage(clock);
        return new CandidateResponse(
                summary.id(),
                summary.fullName(),
                summary.email(),
                summary.phone(),
                summary.source(),
                summary.currentStage(),
                summary.currentStageSince(),
                inStage.toString(),
                DurationFormat.humanise(inStage),
                summary.createdAt());
    }
}
