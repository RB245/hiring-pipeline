package com.pipeline.search;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The AST as SQL. Every line of this class is composition — and, or, not, and walking to
 * the children — and not one of it knows what any field filters on. That is the same
 * bargain file 07 struck with the lexer and the parser, carried into the query layer: if
 * the builder had to grow a branch per field, the claim that a new searchable field is one
 * new class would be false.
 *
 * <p>What it does own is the shape of the tree, which is why the three things that are
 * about shape rather than about fields live here: folding negation into the leaves,
 * enumerating the leaves for the ranking, and naming the top-level conjuncts a zero-result
 * query could drop.
 */
@Component
public class SpecificationBuilder {

    private final FieldRegistry registry;

    public SpecificationBuilder(FieldRegistry registry) {
        this.registry = registry;
    }

    public Predicate predicate(Node node, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return switch (node) {
            case Node.And and -> builder.and(all(and.children(), candidate, query, builder));
            case Node.Or or -> builder.or(all(or.children(), candidate, query, builder));
            case Node.Not not -> builder.not(predicate(not.child(), candidate, query, builder));
            case Node.Predicate predicate -> leaf(predicate, false).predicate(candidate, query, builder);
            case Node.Term term -> leaf(term, false).predicate(candidate, query, builder);
        };
    }

    private Predicate[] all(List<Node> children, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return children.stream()
                .map(child -> predicate(child, candidate, query, builder))
                .toArray(Predicate[]::new);
    }

    /**
     * Every condition in the tree, in the order she wrote them, with negation pushed down
     * so each one can be evaluated on its own. Flat rather than nested because both
     * callers want to ask "how many of these does this row satisfy", which is a question
     * about conditions and not about the shape they were arranged in.
     */
    public List<Leaf> leaves(Node node) {
        List<Leaf> leaves = new ArrayList<>();
        collect(node, false, leaves);
        return List.copyOf(leaves);
    }

    private void collect(Node node, boolean negated, List<Leaf> leaves) {
        switch (node) {
            case Node.And and -> and.children().forEach(child -> collect(child, negated, leaves));
            case Node.Or or -> or.children().forEach(child -> collect(child, negated, leaves));
            case Node.Not not -> collect(not.child(), !negated, leaves);
            case Node.Predicate predicate -> leaves.add(leaf(predicate, negated));
            case Node.Term term -> leaves.add(leaf(term, negated));
        }
    }

    /**
     * The ways this query could be loosened: everything she asked for, minus one of the
     * conditions she joined with an implicit AND.
     *
     * <p>Only the top level, and only a conjunction. Dropping a branch of an OR does not
     * loosen anything — the other branch still matches on its own — and dropping the sole
     * condition of a one-condition query is not advice, it is "search for nothing". Both
     * come back as an empty list rather than as a suggestion that cannot help.
     */
    public List<Relaxation> relaxations(Node node) {
        if (!(node instanceof Node.And and) || and.children().size() < 2) {
            return List.of();
        }
        List<Relaxation> relaxations = new ArrayList<>();
        for (int i = 0; i < and.children().size(); i++) {
            List<Node> remaining = new ArrayList<>(and.children());
            Node dropped = remaining.remove(i);
            Node remainder = remaining.size() == 1
                    ? remaining.get(0)
                    : new Node.And(remaining, and.span(), and.source());
            relaxations.add(new Relaxation(Dsl.render(dropped), remainder));
        }
        return List.copyOf(relaxations);
    }

    private Leaf leaf(Node.Predicate predicate, boolean negated) {
        return new Leaf(registry.find(predicate.field()).orElseThrow(), predicate.resolved(), negated);
    }

    /**
     * A bare word has no field to look up, so it goes to whichever field claimed them.
     * Resolved here rather than by the validator because giving {@link Node.Term} a
     * resolved value would change the AST every golden row in file 07 asserts on, to no
     * benefit: nothing between the validator and here looks at it.
     */
    private Leaf leaf(Node.Term term, boolean negated) {
        FieldHandler handler = registry.bareTermHandler()
                .orElseThrow(() -> new IllegalStateException(
                        "No field claims bare terms, so \"" + term.text() + "\" cannot be searched for"));
        ResolvedValue value = handler.bareTerm(term.text()).orElseThrow();
        return new Leaf(handler, value, negated);
    }
}
