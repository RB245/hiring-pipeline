package com.pipeline.search;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Locale;
import java.util.Optional;

/**
 * One condition of a query, with any enclosing negations already folded in, so that
 * "satisfied" means the same thing for {@code status:hired} and {@code -status:hired}.
 *
 * <p>Exists because two features need to talk about a single condition rather than the
 * whole tree: the specificity term of the ranking, which counts how many conditions a row
 * satisfies, and {@code matchedOn}, which has to name them.
 */
public record Leaf(FieldHandler handler, ResolvedValue value, boolean negated) {

    public Predicate predicate(Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        Predicate predicate = handler.predicate(value, candidate, query, builder);
        return negated ? builder.not(predicate) : predicate;
    }

    /**
     * The field this condition should be retried against when the query found nobody, or
     * empty if there is no more generous way to ask it.
     *
     * <p>Never offered for a negated condition. Loosening "not called Sharma" widens who is
     * excluded, which is the opposite of a suggestion — it can only ever return fewer rows,
     * and the whole point is to return more.
     */
    public Optional<String> loosensTo() {
        return negated ? Optional.empty() : handler.loosensTo();
    }

    /** The text she typed, for naming this condition in a suggestion. */
    public String term() {
        return value instanceof ResolvedValue.TextValue text ? text.text() : "";
    }

    /** The field's own name, so a suggestion can say which one it is talking about. */
    public String field() {
        return handler.field();
    }

    /** True for a positive fuzzy-text condition, which is the one the name score reads. */
    public boolean isText() {
        return !negated && value instanceof ResolvedValue.TextValue;
    }

    public ResolvedValue.TextValue text() {
        return (ResolvedValue.TextValue) value;
    }

    /**
     * How this condition reads in a {@code matchedOn} entry: "stage = Interview", not
     * "stage:interview". She is being told why a row came back, not shown her query again.
     *
     * <p>Switches on the shape of the resolved value rather than on the field's name, so a
     * new field that resolves to a stage explains itself without editing this method.
     */
    public String describe() {
        String is = negated ? " != " : " = ";
        return switch (value) {
            case ResolvedValue.StageValue stage -> handler.field() + is + title(stage.stage().name());
            case ResolvedValue.StatusValue status -> handler.field() + is + title(status.status().name());
            case ResolvedValue.TextValue text ->
                    handler.field() + (negated ? " !~ " : " ~ ") + "'" + text.text() + "'";
            case ResolvedValue.AgeValue age ->
                    handler.field() + " " + (negated ? "not " : "") + age.operator().render() + age.literal();
            case ResolvedValue.DateValue date -> handler.field() + is + date.literal();
            case ResolvedValue.MovedToValue moved -> handler.field() + is + title(moved.stage().name())
                    + moved.since().map(since -> " since " + since.literal()).orElse("")
                    + moved.before().map(before -> " before " + before.literal()).orElse("");
        };
    }

    private static String title(String name) {
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }
}
