package com.pipeline.search;

import java.util.List;

/**
 * The parsed query. Variants are nested rather than given a file each: they are one type
 * with five shapes, not five concepts, and reading the tree in one place is worth more
 * than the symmetry.
 *
 * <p>Both spans reach every node. A parent's is the extent of its children, so underlining
 * the offending part of a nested query is a property lookup rather than a second traversal.
 */
public sealed interface Node {

    Span span();

    Span source();

    record And(List<Node> children, Span span, Span source) implements Node {}

    record Or(List<Node> children, Span span, Span source) implements Node {}

    record Not(Node child, Span span, Span source) implements Node {}

    /**
     * {@code value} is what she typed, {@code resolved} is what it means. The second is
     * null between the parser and the validator and never afterwards: the parser is an
     * internal step and {@link SearchQuery} is only ever built from a validated tree.
     */
    record Predicate(String field, Operator operator, Value value, ResolvedValue resolved, Span span, Span source)
            implements Node {

        Predicate resolvedAs(ResolvedValue value) {
            return new Predicate(field(), operator(), value(), value, span(), source());
        }
    }

    /** A word with no field, matched against name and email. */
    record Term(String text, Span span, Span source) implements Node {}

    /**
     * The right-hand side, before anyone has decided what it means. An absent value is
     * empty text spanning the field and its operator, so {@code stage:} underlines
     * {@code stage:} rather than a zero-width point after it.
     */
    record Value(String text, boolean quoted, Span span, Span source) {

        boolean isMissing() {
            return text.isEmpty();
        }
    }
}
