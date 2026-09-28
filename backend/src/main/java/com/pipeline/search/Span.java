package com.pipeline.search;

/**
 * A half-open {@code [start, end)} character range. Every token, every AST node and every
 * error carries two of these: one into the normalised DSL and one into what the recruiter
 * actually typed. Retrofitting spans is miserable, so nothing in this package is allowed
 * to exist without them.
 */
public record Span(int start, int end) {

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Not a span: [" + start + "," + end + ")");
        }
    }

    /** The smallest span covering both. Used to give a parent node its children's extent. */
    public Span to(Span other) {
        return new Span(Math.min(start, other.start), Math.max(end, other.end));
    }

    public String in(String text) {
        return text.substring(start, end);
    }

    @Override
    public String toString() {
        return "[" + start + "," + end + "]";
    }
}
