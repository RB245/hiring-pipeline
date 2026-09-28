package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.search.fields.SearchFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spans, on their own, because everything else in the package would still pass if they
 * were wrong.
 */
class SpanTest {

    private final SearchQueryParser parser = SearchFixture.parser();

    @Test
    void aQueryAlreadyInTheDslPassesThroughUntouched() {
        String query = "stage:interview in_stage_for:>3d -status:rejected";

        assertThat(Normalizer.normalise(query).text()).isEqualTo(query);
    }

    @Test
    void everyTokenKnowsExactlyWhichCharactersItIs() {
        String query = "stage:interview in_stage_for:>3d -status:rejected";
        List<Token> tokens = new Lexer(Normalizer.normalise(query)).tokens();

        assertThat(tokens).extracting(token -> token.span().in(query))
                .containsExactly("stage", ":", "interview",
                        "in_stage_for", ":", ">", "3d",
                        "-", "status", ":", "rejected", "");
    }

    @Test
    void aQuotedValueSpansItsQuotesButNotItsText() {
        String query = "name:\"priya sharma\"";
        List<Token> tokens = new Lexer(Normalizer.normalise(query)).tokens();

        Token value = tokens.get(2);
        assertThat(value.text()).isEqualTo("priya sharma");
        assertThat(value.span()).isEqualTo(new Span(5, 19));
    }

    @Test
    void comparisonOperatorsTakeTheLongerFormWhenThereIsOne() {
        List<Token> tokens = new Lexer(Normalizer.normalise("in_stage_for:>=2w")).tokens();

        assertThat(tokens).extracting(Token::type).containsExactly(
                TokenType.WORD, TokenType.COLON, TokenType.GREATER_OR_EQUAL, TokenType.WORD, TokenType.END);
    }

    @Test
    void aHyphenInsideAWordIsPartOfIt() {
        List<Token> tokens = new Lexer(Normalizer.normalise("jean-luc")).tokens();

        assertThat(tokens).extracting(Token::type).containsExactly(TokenType.WORD, TokenType.END);
        assertThat(tokens.get(0).text()).isEqualTo("jean-luc");
    }

    @Test
    void anAlreadyDslQueryMapsBackCharacterForCharacter() {
        String query = "stage:interview in_stage_for:>3d -status:rejected";
        Node.And and = (Node.And) parser.parse(query).ast();

        assertThat(and.children().get(0).source()).isEqualTo(new Span(0, 15));
        assertThat(and.children().get(1).source()).isEqualTo(new Span(16, 32));
        assertThat(and.children().get(2).source()).isEqualTo(new Span(33, 49));
        assertThat(and.children().get(0).source().in(query)).isEqualTo("stage:interview");
        assertThat(and.children().get(2).source().in(query)).isEqualTo("-status:rejected");
    }

    /**
     * A rewritten phrase has no character-level correspondence with what it became, so the
     * span is the whole phrase the rule matched. That is what the box should underline
     * anyway: the recruiter wrote "for more than a week", not ">7d".
     */
    @Test
    void aRewrittenPhraseMapsBackToTheWholePhrase() {
        String query = "Who has been stuck in Screening for more than a week?";
        Node.And and = (Node.And) parser.parse(query).ast();

        assertThat(and.children().get(0).source().in(query)).isEqualTo("stuck in Screening");
        assertThat(and.children().get(1).source().in(query)).isEqualTo("for more than a week");
    }

    @Test
    void textThatSurvivedTheNormaliserUntouchedStillMapsExactly() {
        String query = "Who is in Interview? sharam";
        Node.And and = (Node.And) parser.parse(query).ast();

        assertThat(and.children().get(0).source().in(query)).isEqualTo("in Interview");
        assertThat(and.children().get(1).source().in(query)).isEqualTo("sharam");
    }
}
