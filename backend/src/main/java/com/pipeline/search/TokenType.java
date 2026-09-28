package com.pipeline.search;

/**
 * Keywords are deliberately absent. {@code and}, {@code or}, {@code not} and {@code except}
 * lex as ordinary words and only become connectives where the grammar expects one, so that
 * {@code name:not} searches for somebody called Not rather than failing to parse.
 */
enum TokenType {
    WORD,
    QUOTED,
    COLON,
    EQUALS,
    GREATER_THAN,
    GREATER_OR_EQUAL,
    LESS_THAN,
    LESS_OR_EQUAL,
    LEFT_PAREN,
    RIGHT_PAREN,
    COMMA,
    MINUS,
    END;

    boolean isOperator() {
        return this == COLON || this == EQUALS || isComparison();
    }

    boolean isComparison() {
        return this == GREATER_THAN || this == GREATER_OR_EQUAL
                || this == LESS_THAN || this == LESS_OR_EQUAL;
    }
}
