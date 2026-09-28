package com.pipeline.search;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Semantics. The parser has already decided the query is well formed; this decides whether
 * it means anything — that the field exists, that it accepts the comparison asked of it,
 * that {@code Intervew} is not a stage, that {@code banana} is not a duration.
 *
 * <p>It also binds modifiers. {@code since:} and {@code before:} do not filter on their own:
 * they bound the {@code moved_to:} they sit beside, so they are folded into it and removed
 * from the tree. A modifier with nothing to bind to is an error rather than a no-op,
 * because dropping half a filter silently returns rows the recruiter did not ask for and
 * she has no way to notice.
 */
final class Validator {

    private final FieldRegistry registry;
    private final NormalizedQuery query;
    private final Clock clock;

    Validator(FieldRegistry registry, NormalizedQuery query, Clock clock) {
        this.registry = registry;
        this.query = query;
        this.clock = clock;
    }

    Node validate(Node node) {
        // Structure before meaning, for the same reason the parser runs before the
        // validator: "you left the value off since:" is a more useful thing to be told
        // than "x is not a stage" when the query has both problems, because the first is
        // a half-finished thought and the second is a typo in a finished one.
        checkShape(node);
        Node resolved = attach(resolve(node));
        rejectUnattachedModifiers(resolved);
        return resolved;
    }

    private void checkShape(Node node) {
        switch (node) {
            case Node.And and -> and.children().forEach(this::checkShape);
            case Node.Or or -> or.children().forEach(this::checkShape);
            case Node.Not not -> checkShape(not.child());
            case Node.Predicate predicate -> {
                FieldHandler handler = registry.find(predicate.field()).orElseThrow(() -> unknownField(predicate));
                if (!handler.operators().contains(predicate.operator())) {
                    throw unsupportedOperator(predicate, handler);
                }
                if (predicate.value().isMissing()) {
                    throw missingValue(predicate, handler);
                }
            }
            case Node.Term term -> { }
        }
    }

    private Node resolve(Node node) {
        return switch (node) {
            case Node.And and -> new Node.And(resolveAll(and.children()), and.span(), and.source());
            case Node.Or or -> new Node.Or(resolveAll(or.children()), or.span(), or.source());
            case Node.Not not -> new Node.Not(resolve(not.child()), not.span(), not.source());
            case Node.Predicate predicate -> resolve(predicate);
            case Node.Term term -> term;
        };
    }

    private List<Node> resolveAll(List<Node> children) {
        return children.stream().map(this::resolve).toList();
    }

    /** Shape is already checked, so the handler is present and the value is there to read. */
    private Node resolve(Node.Predicate predicate) {
        FieldHandler handler = registry.find(predicate.field()).orElseThrow();
        return predicate.resolvedAs(handler.resolve(predicate.value(), predicate.operator(), clock));
    }

    private Node attach(Node node) {
        return switch (node) {
            case Node.And and -> attachWithin(and);
            case Node.Or or -> new Node.Or(or.children().stream().map(this::attach).toList(), or.span(), or.source());
            case Node.Not not -> new Node.Not(attach(not.child()), not.span(), not.source());
            default -> node;
        };
    }

    /**
     * Modifiers bind inside the conjunction they were written in, which is the only
     * reading that survives an OR: "moved to Offer since Monday OR moved to Hired" must
     * not date-bound the second half.
     */
    private Node attachWithin(Node.And and) {
        List<Node> children = new ArrayList<>(and.children().stream().map(this::attach).toList());
        List<Node> consumed = new ArrayList<>();

        for (int i = 0; i < children.size(); i++) {
            if (!(children.get(i) instanceof Node.Predicate predicate)) {
                continue;
            }
            FieldHandler handler = registry.find(predicate.field()).orElseThrow();
            if (handler.modifiers().isEmpty()) {
                continue;
            }
            Map<String, ResolvedValue> found = new HashMap<>();
            for (Node sibling : children) {
                if (sibling instanceof Node.Predicate other && handler.modifiers().contains(other.field())) {
                    found.put(other.field(), other.resolved());
                    consumed.add(sibling);
                }
            }
            if (!found.isEmpty()) {
                children.set(i, predicate.resolvedAs(handler.attach(predicate.resolved(), found)));
            }
        }

        children.removeAll(consumed);
        // A conjunction of one is the thing itself, which is what is left once "moved_to
        // ... since ..." has folded into a single predicate.
        return children.size() == 1 ? children.get(0) : new Node.And(children, and.span(), and.source());
    }

    private void rejectUnattachedModifiers(Node node) {
        switch (node) {
            case Node.And and -> and.children().forEach(this::rejectUnattachedModifiers);
            case Node.Or or -> or.children().forEach(this::rejectUnattachedModifiers);
            case Node.Not not -> rejectUnattachedModifiers(not.child());
            case Node.Predicate predicate -> {
                if (registry.isModifier(predicate.field())) {
                    throw new SearchQueryException(ErrorCode.UNATTACHED_MODIFIER,
                            predicate.field() + ": only means something next to a field it can modify,"
                                    + " such as moved_to:interview " + predicate.field() + ":monday.",
                            predicate.span(), predicate.source());
                }
            }
            case Node.Term term -> { }
        }
    }

    private SearchQueryException unknownField(Node.Predicate predicate) {
        Span span = new Span(predicate.span().start(), predicate.span().start() + predicate.field().length());
        return new SearchQueryException(ErrorCode.UNKNOWN_FIELD,
                "There is no field called \"" + predicate.field() + "\". Valid fields are "
                        + String.join(", ", registry.fields()) + ".",
                span, query.sourceSpan(span), Levenshtein.closest(predicate.field(), registry.fields()));
    }

    private SearchQueryException unsupportedOperator(Node.Predicate predicate, FieldHandler handler) {
        return new SearchQueryException(ErrorCode.UNSUPPORTED_OPERATOR,
                predicate.field() + ": cannot be compared with " + predicate.operator().render()
                        + ". Try " + example(handler) + ".",
                predicate.span(), predicate.source(), List.of(example(handler)));
    }

    private SearchQueryException missingValue(Node.Predicate predicate, FieldHandler handler) {
        return new SearchQueryException(ErrorCode.MISSING_VALUE,
                predicate.field() + ": needs " + handler.valueKind() + ". Try " + example(handler) + ".",
                predicate.value().span(), predicate.value().source(),
                handler.examples().stream().map(value -> handler.field() + ":" + value).toList());
    }

    private static String example(FieldHandler handler) {
        return handler.field() + ":" + handler.examples().get(0);
    }
}
