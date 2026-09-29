package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pipeline.search.fields.SearchFixture;
import org.junit.jupiter.api.Test;

/**
 * The error catalogue, asserting the span as hard as the message. A wrong span is the
 * failure that looks fine in a test that only reads the sentence: the message is right,
 * the underline is two characters out, and nobody notices until the box highlights the
 * wrong word.
 */
class SearchErrorTest {

    private final SearchQueryParser parser = SearchFixture.parser();

    @Test
    void aMisspeltStageNamesTheStageAndOffersTheRealOne() {
        SearchQueryException thrown = parse("stage:Intervew");

        assertThat(thrown.code()).isEqualTo(ErrorCode.UNKNOWN_STAGE);
        assertThat(thrown).hasMessageContaining("Intervew");
        assertThat(thrown.didYouMean()).containsExactly("Interview");
        // The value, not the whole predicate: "stage:" is fine, "Intervew" is not.
        assertThat(thrown.span()).isEqualTo(new Span(6, 14));
        assertThat(thrown.source()).isEqualTo(new Span(6, 14));
    }

    @Test
    void aDurationThatIsNotOneSaysWhatOneLooksLike() {
        SearchQueryException thrown = parse("in_stage_for:>banana");

        assertThat(thrown.code()).isEqualTo(ErrorCode.BAD_DURATION);
        assertThat(thrown).hasMessageContaining("banana").hasMessageContaining("7d").hasMessageContaining("3mo");
        assertThat(thrown.span()).isEqualTo(new Span(14, 20));
    }

    @Test
    void aFieldWithNoValueSuggestsOne() {
        SearchQueryException thrown = parse("stage:");

        assertThat(thrown.code()).isEqualTo(ErrorCode.MISSING_VALUE);
        assertThat(thrown).hasMessageContaining("stage: needs a stage").hasMessageContaining("stage:applied");
        assertThat(thrown.didYouMean()).contains("stage:screening");
        // The field and its colon, so the box underlines something rather than nothing.
        assertThat(thrown.span()).isEqualTo(new Span(0, 6));
    }

    @Test
    void aModifierWithNoValueIsReportedBeforeTheStageBesideItIsResolved() {
        SearchQueryException thrown = parse("moved_to:x since:");

        assertThat(thrown.code()).isEqualTo(ErrorCode.MISSING_VALUE);
        assertThat(thrown).hasMessageContaining("since: needs a date");
        assertThat(thrown.span()).isEqualTo(new Span(11, 17));
    }

    @Test
    void anUnclosedGroupNamesWhereItOpened() {
        SearchQueryException thrown = parse("(stage:offer");

        assertThat(thrown.code()).isEqualTo(ErrorCode.UNCLOSED_GROUP);
        assertThat(thrown).hasMessageContaining("character 0");
        assertThat(thrown.span()).isEqualTo(new Span(0, 1));
    }

    @Test
    void anUnknownFieldListsTheOnesThatExist() {
        SearchQueryException thrown = parse("frobnicate:yes");

        assertThat(thrown.code()).isEqualTo(ErrorCode.UNKNOWN_FIELD);
        assertThat(thrown).hasMessageContaining("frobnicate")
                .hasMessageContaining(
                        "applied, before, in_stage_for, moved_to, name, name_like, reached, since, source, stage, status");
        assertThat(thrown.span()).isEqualTo(new Span(0, 10));
    }

    @Test
    void aNearlyRightFieldGetsTheSuggestion() {
        assertThat(parse("stagee:interview").didYouMean()).containsExactly("stage");
    }

    @Test
    void aNearlyRightStatusGetsTheSuggestion() {
        SearchQueryException thrown = parse("status:hirred");

        assertThat(thrown.code()).isEqualTo(ErrorCode.UNKNOWN_STATUS);
        assertThat(thrown.didYouMean()).containsExactly("hired");
        assertThat(thrown.span()).isEqualTo(new Span(7, 13));
    }

    @Test
    void aDateThatIsNotOneSaysWhatOneLooksLike() {
        SearchQueryException thrown = parse("moved_to:offer since:banan");

        assertThat(thrown.code()).isEqualTo(ErrorCode.BAD_DATE);
        assertThat(thrown).hasMessageContaining("monday").hasMessageContaining("2025-03-11");
        assertThat(thrown.span()).isEqualTo(new Span(21, 26));
    }

    @Test
    void aModifierWithNothingToModifyIsAnErrorRatherThanIgnored() {
        SearchQueryException thrown = parse("since:monday");

        assertThat(thrown.code()).isEqualTo(ErrorCode.UNATTACHED_MODIFIER);
        assertThat(thrown).hasMessageContaining("moved_to:interview since:monday");
        assertThat(thrown.span()).isEqualTo(new Span(0, 12));
    }

    @Test
    void aStageCannotBeCompared() {
        SearchQueryException thrown = parse("stage:>interview");

        assertThat(thrown.code()).isEqualTo(ErrorCode.UNSUPPORTED_OPERATOR);
        assertThat(thrown).hasMessageContaining("stage: cannot be compared");
    }

    @Test
    void anUnclosedQuoteIsReportedFromTheQuote() {
        SearchQueryException thrown = parse("name:\"priya");

        assertThat(thrown.code()).isEqualTo(ErrorCode.UNCLOSED_QUOTE);
        assertThat(thrown.span()).isEqualTo(new Span(5, 11));
    }

    @Test
    void aQueryOfNothingButFillerSaysSoRatherThanMatchingEveryone() {
        SearchQueryException thrown = parse("who is the?");

        assertThat(thrown.code()).isEqualTo(ErrorCode.EMPTY_QUERY);
        assertThat(thrown).hasMessageContaining("nothing in it to filter on");
        assertThat(thrown.didYouMean()).contains("stage:interview");
    }

    @Test
    void anEmptyQueryIsRejectedRatherThanMatchingEveryone() {
        assertThat(parse("   ").code()).isEqualTo(ErrorCode.EMPTY_QUERY);
    }

    private SearchQueryException parse(String query) {
        return catchThrowableOfType(SearchQueryException.class, () -> parser.parse(query));
    }
}
