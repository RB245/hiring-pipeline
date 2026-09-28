package com.pipeline.search;

/**
 * A lexeme and where it came from, twice over: {@code span} into the normalised DSL the
 * parser is working on, {@code source} into the query the recruiter typed.
 */
record Token(TokenType type, String text, Span span, Span source) {

    boolean is(TokenType candidate) {
        return type == candidate;
    }

    /** Case-insensitive, because {@code OR} and {@code or} are the same connective. */
    boolean isWord(String keyword) {
        return type == TokenType.WORD && text.equalsIgnoreCase(keyword);
    }
}
