package com.pipeline.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pipeline.application.CandidateSearch;
import io.swagger.v3.oas.annotations.media.Schema;
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
                @Schema(description = "Search only, and only when it found nobody: what to loosen.")
                List<Relaxation> suggestions) {

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
            List<CandidateSearch.Relaxation> suggestions) {
        return new CandidatePageResponse(
                candidates, nextCursor, query, suggestions.stream().map(Relaxation::of).toList());
    }

    /** "without in_stage_for:&gt;7d — 6 results". */
    record Relaxation(
            @Schema(description = "The condition to drop, in canonical form", example = "in_stage_for:>7d")
                    String without,
            @Schema(description = "How many candidates the rest of the query matches", example = "6")
                    long results) {

        static Relaxation of(CandidateSearch.Relaxation relaxation) {
            return new Relaxation(relaxation.without(), relaxation.results());
        }
    }
}
