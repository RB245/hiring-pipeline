package com.pipeline.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.SearchHit;
import com.pipeline.domain.Stage;
import com.pipeline.domain.TransitionRules;
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
 *
 * <p>{@code legalTargets} is here so that a client can render the moves a candidate has
 * without knowing the rules that produced them. It is the same list the 422 body carries
 * when an illegal move is attempted, from the same {@link TransitionRules}, so a button the
 * board offers and a move the API accepts cannot disagree. Without it the board would have
 * to keep its own copy of the state machine, and the point of deriving legal moves by
 * filtering the stages through the rules was that there is only ever one copy.
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
        @Schema(
                        description = "The moves this candidate has from here. Empty means terminal.",
                        example = "[\"HIRED\", \"REJECTED\"]")
                List<Stage> legalTargets,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(description = "Search only. 0 to 1, best first.", example = "0.86")
                Double score,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(
                        description = "Search only. Why this candidate came back.",
                        example = "[\"name ~ 'sharam' (0.80)\", \"stage = Interview\"]")
                List<String> matchedOn) {

    static CandidateResponse of(CandidateSummary summary, Clock clock, TransitionRules rules) {
        return of(summary, clock, rules, null, null);
    }

    static CandidateResponse of(SearchHit hit, Clock clock, TransitionRules rules) {
        return of(hit.candidate(), clock, rules, hit.score(), hit.matchedOn());
    }

    private static CandidateResponse of(
            CandidateSummary summary, Clock clock, TransitionRules rules, Double score, List<String> matchedOn) {
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
                rules.legalTargets(summary.currentStage()),
                score,
                matchedOn);
    }
}
