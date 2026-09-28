package com.pipeline.search;

import java.util.List;

/**
 * A query language is an attack surface, so the work is bounded before any of it is done.
 * All three limits are checked without parsing: length against the raw input, which is
 * what an attacker controls, and the other two against the token stream, where a predicate
 * is a field with an operator and nesting is just paren balance.
 */
final class QueryGuards {

    static final int MAX_LENGTH = 512;
    static final int MAX_PREDICATES = 12;
    static final int MAX_DEPTH = 5;

    static void checkLength(String raw) {
        if (raw.length() > MAX_LENGTH) {
            Span span = new Span(MAX_LENGTH, raw.length());
            throw new SearchQueryException(ErrorCode.QUERY_TOO_LONG,
                    "That query is " + raw.length() + " characters. The limit is " + MAX_LENGTH + ".", span, span);
        }
    }

    static void checkShape(List<Token> tokens) {
        checkPredicates(tokens);
        checkDepth(tokens);
    }

    private static void checkPredicates(List<Token> tokens) {
        int count = 0;
        int i = 0;
        while (!at(tokens, i).is(TokenType.END)) {
            Token token = at(tokens, i);
            if (token.is(TokenType.WORD) || token.is(TokenType.QUOTED)) {
                // A bare term filters as much as a predicate does and costs as much to
                // run, so it counts against the same budget.
                count++;
                if (count > MAX_PREDICATES) {
                    throw new SearchQueryException(ErrorCode.TOO_MANY_PREDICATES,
                            "That query has more than " + MAX_PREDICATES + " conditions. Narrow it down.",
                            token.span(), token.source());
                }
                i += predicateLength(tokens, i);
            } else {
                i++;
            }
        }
    }

    /** How many tokens the predicate starting here occupies, so its value is not counted twice. */
    private static int predicateLength(List<Token> tokens, int start) {
        if (!at(tokens, start + 1).type().isOperator()) {
            return 1;
        }
        int length = at(tokens, start + 2).type().isComparison() ? 3 : 2;
        Token value = at(tokens, start + length);
        boolean hasValue = value.is(TokenType.QUOTED)
                || value.is(TokenType.WORD) && !at(tokens, start + length + 1).type().isOperator();
        return hasValue ? length + 1 : length;
    }

    private static void checkDepth(List<Token> tokens) {
        int depth = 0;
        for (Token token : tokens) {
            if (token.is(TokenType.LEFT_PAREN)) {
                depth++;
                if (depth > MAX_DEPTH) {
                    throw new SearchQueryException(ErrorCode.TOO_DEEPLY_NESTED,
                            "That query nests more than " + MAX_DEPTH + " groups deep.",
                            token.span(), token.source());
                }
            } else if (token.is(TokenType.RIGHT_PAREN)) {
                depth--;
            }
        }
    }

    /** The stream always ends with END, so reading past the end reads END rather than failing. */
    private static Token at(List<Token> tokens, int index) {
        return tokens.get(Math.min(index, tokens.size() - 1));
    }

    private QueryGuards() {}
}
