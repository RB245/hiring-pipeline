package com.pipeline.api;

import com.pipeline.application.SearchCandidates;
import com.pipeline.search.Suggester;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints that are about the query rather than about the results. Running a
 * search lives on the candidates list, because a search is a filtered list and giving it a
 * second URL would mean two ways to page through candidates.
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search")
class SearchController {

    private final SearchCandidates search;
    private final Suggester suggester;

    SearchController(SearchCandidates search, Suggester suggester) {
        this.search = search;
        this.suggester = suggester;
    }

    @GetMapping("/explain")
    @Operation(
            summary = "Show how a query was understood, without running it",
            description =
                    """
                    Returns the canonical DSL, the parse tree, and every relative date and duration
                    resolved against the server's clock — so "since Monday" comes back as the Monday
                    it means.

                    A query that cannot be parsed fails here exactly as it would on the list, with the
                    same code and the same spans, which makes this the cheapest way to find out why.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The parse"),
        @ApiResponse(responseCode = "422", description = "Query is invalid; body carries the span to underline")
    })
    ExplainResponse explain(
            @Parameter(description = "Natural language or DSL", example = "Who moved to Interview since Monday?")
                    @RequestParam
                    String q) {
        return ExplainResponse.of(search.explain(q));
    }

    @GetMapping("/suggest")
    @Operation(
            summary = "Complete a half-typed query",
            description =
                    """
                    Field names where she has not yet typed a colon, and that field's values where she
                    has. Deliberately tolerant: the input is expected to be invalid, since a query is
                    invalid for as long as it is being typed.
                    """)
    SuggestResponse suggest(
            @Parameter(description = "Whatever is in the box so far", example = "stage:in") @RequestParam(required = false)
                    String q) {
        return SuggestResponse.of(suggester.suggest(q));
    }
}
