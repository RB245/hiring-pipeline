package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.search.fields.SearchFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Autocomplete, whose whole difficulty is that everything it is asked about is invalid.
 * A query is invalid for as long as it is being typed, so every case here would be
 * rejected by the parser, and suggesting nothing would be the one unhelpful answer.
 */
class SuggesterTest {

    private final Suggester suggester = new Suggester(SearchFixture.registry());

    private List<String> completions(String query) {
        return suggester.suggest(query).completions().stream()
                .map(Suggester.Completion::value)
                .toList();
    }

    @Test
    void anEmptyBoxOffersEveryField() {
        assertThat(completions("")).containsExactly(
                "applied:", "before:", "in_stage_for:", "moved_to:", "name:", "name_like:",
                "reached:", "since:", "stage:", "status:");
    }

    @Test
    void aPartialFieldNarrowsToTheFieldsThatStartWithIt() {
        assertThat(completions("sta")).containsExactly("stage:", "status:");
    }

    @Test
    void pastTheColonItOffersThatFieldsValues() {
        assertThat(completions("stage:")).containsExactly(
                "stage:applied", "stage:screening", "stage:interview",
                "stage:offer", "stage:hired", "stage:rejected");
    }

    @Test
    void andNarrowsThemToo() {
        assertThat(completions("stage:in")).containsExactly("stage:interview");
    }

    /** Only the word she is on. Everything before it is a finished condition. */
    @Test
    void itCompletesTheLastWordRatherThanTheWholeBox() {
        assertThat(completions("stage:interview stat")).containsExactly("status:");
        assertThat(suggester.suggest("stage:interview stat").replacing()).isEqualTo(new Span(16, 20));
    }

    /** A negation is part of the query, not of the word, and has to survive being completed. */
    @Test
    void aLeadingMinusIsKeptOnEveryCompletion() {
        assertThat(completions("-stat")).containsExactly("-status:");
        assertThat(completions("-status:re")).containsExactly("-status:rejected");
    }

    /**
     * An unclosed quote holds a word together across the space inside it, so the token
     * being completed is the whole {@code name:"priya sh} rather than a stray {@code sh}.
     */
    @Test
    void anUnclosedQuoteDoesNotSplitTheToken() {
        assertThat(completions("name:\"priya sh")).containsExactly("name:\"priya sharma\"");
        assertThat(suggester.suggest("name:\"priya sh").replacing()).isEqualTo(new Span(0, 14));
    }

    /** A comparison narrows to the examples that use one, rather than to a single guess. */
    @Test
    void durationsAreOfferedForTheFieldsThatTakeThem() {
        assertThat(completions("in_stage_for:>"))
                .containsExactly("in_stage_for:>7d", "in_stage_for:>2w", "in_stage_for:>3mo");
        assertThat(completions("in_stage_for:>7")).containsExactly("in_stage_for:>7d");
    }

    @Test
    void anUnknownFieldOffersNothingRatherThanGuessing() {
        assertThat(completions("frobnicate:")).isEmpty();
    }

    @Test
    void aNullQueryIsTheSameAsAnEmptyOne() {
        assertThat(completions(null)).isEqualTo(completions(""));
    }

    /**
     * The claim the registry exists to make good on, at the autocomplete end of it: a new
     * searchable field appears here without this class being touched.
     */
    @Test
    void aNewFieldAutocompletesWithoutTouchingThisClass() {
        assertThat(completions("in_")).containsExactly("in_stage_for:");
        assertThat(completions("name")).containsExactly("name:", "name_like:");
    }
}
