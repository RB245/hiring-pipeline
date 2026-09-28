package com.pipeline.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalised DSL to tokens. Every token gets its span here and carries it to the end;
 * nothing downstream ever has to work out where something was.
 */
final class Lexer {

    /** Everything that ends a word. Note the absence of {@code -}: see {@link #word()}. */
    private static final String DELIMITERS = "\"(),:=<>";

    private final NormalizedQuery query;
    private final String text;
    private int position;

    Lexer(NormalizedQuery query) {
        this.query = query;
        this.text = query.text();
    }

    List<Token> tokens() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (position >= text.length()) {
                tokens.add(token(TokenType.END, position, position));
                return tokens;
            }
            tokens.add(next());
        }
    }

    private Token next() {
        return switch (text.charAt(position)) {
            case '"' -> quoted();
            case '(' -> single(TokenType.LEFT_PAREN);
            case ')' -> single(TokenType.RIGHT_PAREN);
            case ',' -> single(TokenType.COMMA);
            case ':' -> single(TokenType.COLON);
            case '=' -> single(TokenType.EQUALS);
            // Maximal munch, or ">=7d" would lex as a greater-than followed by "=7d".
            case '>' -> comparison(TokenType.GREATER_OR_EQUAL, TokenType.GREATER_THAN);
            case '<' -> comparison(TokenType.LESS_OR_EQUAL, TokenType.LESS_THAN);
            // Only a negation where a token starts. Anywhere else it is a hyphen, and
            // "jean-luc" is one word rather than a search for Jean excluding Luc.
            case '-' -> single(TokenType.MINUS);
            default -> word();
        };
    }

    private Token single(TokenType type) {
        int start = position;
        position++;
        return token(type, start, position);
    }

    private Token comparison(TokenType withEquals, TokenType bare) {
        int start = position;
        position++;
        if (position < text.length() && text.charAt(position) == '=') {
            position++;
            return token(withEquals, start, position);
        }
        return token(bare, start, position);
    }

    private Token quoted() {
        int start = position;
        position++;
        int contentStart = position;
        while (position < text.length() && text.charAt(position) != '"') {
            position++;
        }
        if (position >= text.length()) {
            Span span = new Span(start, text.length());
            throw new SearchQueryException(ErrorCode.UNCLOSED_QUOTE,
                    "This quote is never closed. Add a closing \" or drop the opening one.",
                    span, query.sourceSpan(span));
        }
        String content = text.substring(contentStart, position);
        position++;
        return new Token(TokenType.QUOTED, content, new Span(start, position),
                query.sourceSpan(new Span(start, position)));
    }

    private Token word() {
        int start = position;
        while (position < text.length()
                && !Character.isWhitespace(text.charAt(position))
                && DELIMITERS.indexOf(text.charAt(position)) < 0) {
            position++;
        }
        return token(TokenType.WORD, start, position);
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private Token token(TokenType type, int start, int end) {
        Span span = new Span(start, end);
        return new Token(type, text.substring(start, end), span, query.sourceSpan(span));
    }
}
