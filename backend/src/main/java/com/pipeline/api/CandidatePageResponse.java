package com.pipeline.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import com.pipeline.application.Suggestion;
import java.util.List;

/**
 * One page, whether it came from the list or from a search. The search-only fields are
 * absent rather than empty on a plain list, so a client can tell "this was not a search"
 * from "this search found nothing" — which is the whole distinction {@code suggestions}
 * exists to act on.
 */
public record CandidatePageResponse(
        List<CandidateResponse> candidates,
        @Schema(description = "Pass back as ?cursor= for the next page. Null on the last page.")
                String nextCursor,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(
                        description = "Search only. How the query was read; run it through /search/explain for more.",
                        example = "stage:screening in_stage_for:>7d")
                String query,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(description = "Search only, and only when it found nobody: what to try instead.")
                List<SuggestionResponse> suggestions) {

    static CandidatePageResponse list(List<CandidateResponse> candidates, String nextCursor) {
        return new CandidatePageResponse(candidates, nextCursor, null, null);
    }

    /**
     * Suggestions are a list even when empty, because on a search "we found nothing and
     * have nothing to suggest" is a real answer and should not look like a plain list.
     */
    static CandidatePageResponse search(
            List<CandidateResponse> candidates,
            String nextCursor,
            String query,
            List<Suggestion> suggestions) {
        return new CandidatePageResponse(
                candidates, nextCursor, query, suggestions.stream().map(SuggestionResponse::of).toList());
    }

    /**
     * Something to try instead. {@code query} is a complete DSL string that returns exactly
     * {@code results} candidates, so acting on a suggestion is putting it in the search box
     * and resubmitting — the same gesture whether it came from dropping a condition or from
     * spelling a name more loosely, with no string handling and no need to tell them apart.
     */
    record SuggestionResponse(
            @Schema(description = "The phrase to show her", example = "without in_stage_for:>7d")
                    String suggestion,
            @Schema(
                            description = "Put this in the search box and resubmit",
                            example = "stage:screening -status:rejected")
                    String query,
            @Schema(description = "Exactly how many candidates that query returns", example = "6") long results) {

        static SuggestionResponse of(Suggestion suggestion) {
            return new SuggestionResponse(suggestion.label(), suggestion.query(), suggestion.results());
        }
    }
}
