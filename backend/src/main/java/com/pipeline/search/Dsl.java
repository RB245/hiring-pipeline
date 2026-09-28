package com.pipeline.search;

import java.util.stream.Collectors;

/**
 * The AST back out as canonical DSL. Rendering from the tree rather than handing back
 * whatever the normaliser produced is what makes the canonical form exact: spacing,
 * quoting and the choice between {@code except} and {@code -} are decided in one place,
 * so two queries that mean the same thing print the same way.
 */
final class Dsl {

    private static final String NEEDS_QUOTING = " \t\"(),:=<>";

    static String render(Node node) {
        return switch (node) {
            case Node.Or or -> or.children().stream().map(Dsl::render).collect(Collectors.joining(" OR "));
            case Node.And and -> and.children().stream().map(Dsl::underAnd).collect(Collectors.joining(" "));
            case Node.Not not -> "-" + underNot(not.child());
            case Node.Predicate predicate ->
                    predicate.field() + ":" + predicate.operator().render() + value(predicate.value().text());
            case Node.Term term -> value(term.text());
        };
    }

    /** Whitespace binds tighter than OR, so a disjunction inside a conjunction needs its parens back. */
    private static String underAnd(Node node) {
        return node instanceof Node.Or ? "(" + render(node) + ")" : render(node);
    }

    private static String underNot(Node node) {
        return node instanceof Node.Or || node instanceof Node.And ? "(" + render(node) + ")" : render(node);
    }

    /** Quotes only where they change the parse, so {@code name:"priya"} canonicalises to {@code name:priya}. */
    private static String value(String text) {
        if (text.isEmpty()) {
            return "\"\"";
        }
        boolean needed = text.chars().anyMatch(c -> NEEDS_QUOTING.indexOf(c) >= 0);
        return needed ? "\"" + text + "\"" : text;
    }

    private Dsl() {}
}
