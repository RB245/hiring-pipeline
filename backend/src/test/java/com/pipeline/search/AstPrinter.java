package com.pipeline.search;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The AST as an s-expression, for the golden file. Prints the resolved value rather than
 * the text it came from, so a row proves the validator ran; prints the literal rather than
 * the instant, so the file does not have to be rewritten whenever the fixed clock moves.
 */
final class AstPrinter {

    static String print(Node node) {
        return switch (node) {
            case Node.And and -> "(and " + children(and.children()) + ")";
            case Node.Or or -> "(or " + children(or.children()) + ")";
            case Node.Not not -> "(not " + print(not.child()) + ")";
            case Node.Term term -> "(term " + term.text() + ")";
            case Node.Predicate predicate -> "(" + predicate.field() + " " + symbol(effectiveOperator(predicate))
                    + " " + describe(predicate.resolved()) + ")";
        };
    }

    /**
     * An age predicate written without a comparison defaults to one, and the golden file
     * should show the default rather than hide it.
     */
    private static Operator effectiveOperator(Node.Predicate predicate) {
        return predicate.resolved() instanceof ResolvedValue.AgeValue age ? age.operator() : predicate.operator();
    }

    private static String describe(ResolvedValue value) {
        return switch (value) {
            case ResolvedValue.StageValue stage -> stage.stage().name().toLowerCase();
            case ResolvedValue.StatusValue status -> status.status().name().toLowerCase();
            case ResolvedValue.TextValue text -> "\"" + text.text() + "\"";
            case ResolvedValue.DateValue date -> date.literal();
            case ResolvedValue.AgeValue age -> age.literal();
            case ResolvedValue.MovedToValue moved -> moved.stage().name().toLowerCase()
                    + moved.since().map(since -> " since " + since.literal()).orElse("")
                    + moved.before().map(before -> " before " + before.literal()).orElse("");
        };
    }

    private static String symbol(Operator operator) {
        return operator == Operator.EQUALS ? "=" : operator.render();
    }

    private static String children(List<Node> nodes) {
        return nodes.stream().map(AstPrinter::print).collect(Collectors.joining(" "));
    }

    private AstPrinter() {}
}
