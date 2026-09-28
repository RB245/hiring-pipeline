package com.pipeline.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.SearchHit;
import com.pipeline.domain.Stage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code score} and {@code matchedOn} are present only on a search and absent from a plain
 * list, rather than null or zero, because a candidate has no relevance outside a question.
 * Omitted per property rather than by annotating the record, which would also hide a null
 * phone number and change what the list has always returned.
 */
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
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(description = "Search only. 0 to 1, best first.", example = "0.86")
                Double score,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(
                        description = "Search only. Why this candidate came back.",
                        example = "[\"name ~ 'sharam' (0.80)\", \"stage = Interview\"]")
                List<String> matchedOn) {

    static CandidateResponse of(CandidateSummary summary, Clock clock) {
        return of(summary, clock, null, null);
    }

    static CandidateResponse of(SearchHit hit, Clock clock) {
        return of(hit.candidate(), clock, hit.score(), hit.matchedOn());
    }

    private static CandidateResponse of(
            CandidateSummary summary, Clock clock, Double score, List<String> matchedOn) {
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
                summary.createdAt(),
                score,
                matchedOn);
    }
}
