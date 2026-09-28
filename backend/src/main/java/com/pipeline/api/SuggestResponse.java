package com.pipeline.api;

import com.pipeline.search.Suggester;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Autocomplete. {@code replacing} is the half-written token the completions stand in for,
 * so the caller splices rather than guessing where the word began — which it cannot do
 * safely, because a quoted value holds a space inside it.
 */
@Schema(description = "Completions for a half-typed query")
record SuggestResponse(
        @Schema(description = "[start, end) of the token being completed") List<Integer> replacing,
        List<Completion> completions) {

    static SuggestResponse of(Suggester.Suggestions suggestions) {
        return new SuggestResponse(
                List.of(suggestions.replacing().start(), suggestions.replacing().end()),
                suggestions.completions().stream().map(Completion::of).toList());
    }

    record Completion(
            @Schema(description = "Put this in place of the token", example = "stage:interview") String value,
            @Schema(description = "What to show in the dropdown", example = "interview") String label,
            @Schema(example = "VALUE") Suggester.Kind kind) {

        static Completion of(Suggester.Completion completion) {
            return new Completion(completion.value(), completion.label(), completion.kind());
        }
    }
}
