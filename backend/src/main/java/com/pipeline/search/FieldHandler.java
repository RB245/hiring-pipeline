package com.pipeline.search;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One searchable field, and everything that is specific to it: which comparisons it
 * accepts, what its values mean, and what to suggest when she leaves one out. Nothing
 * outside an implementation knows any field's name — the lexer, the parser, the validator
 * and the guards are all written against this interface — so a new field is a new class
 * and a bean definition, and nothing else.
 *
 * <p>File 08 adds {@link #predicate} here, turning a resolved value into SQL. That is the
 * same bargain: the builder composes and/or/not and the handler supplies the predicate.
 */
public interface FieldHandler {

    /** The name as it appears before the colon. Lower case, underscores, no spaces. */
    String field();

    /** Equality only unless the field says otherwise. */
    default Set<Operator> operators() {
        return Set.of(Operator.EQUALS);
    }

    /**
     * What the value means, or a {@link SearchQueryException} naming the span that is
     * wrong. The clock is passed rather than held so that resolution is a pure function of
     * the query and the current time.
     */
    ResolvedValue resolve(Node.Value value, Operator operator, Clock clock);

    /**
     * The SQL half: what this field filters on, given a value it has already resolved.
     *
     * <p>The signature is Spring's {@code Specification.toPredicate} without the entity
     * type parameter. That omission is deliberate and is what keeps a JPA entity class out
     * of this package: every column a handler needs is reached by attribute name, so the
     * search layer never has to import the persistence layer to describe a filter. The
     * adapter wraps whatever comes back into a {@code Specification} for the repository.
     *
     * <p>Abstract rather than defaulted, so that a new searchable field is a compile error
     * until it says how it filters. A default returning "true" would make a field that
     * silently matched everyone, which is the one failure the recruiter cannot see.
     */
    Predicate predicate(ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder);

    /**
     * How a bare word with no field in front of it should be read, or empty if this field
     * does not claim them. At most one handler may; {@link FieldRegistry} checks that at
     * startup rather than letting two fields race for the same input.
     *
     * <p>Declared here rather than hard-wired in the builder for the same reason as
     * everything else on this interface: "a bare word means a name" is a decision about a
     * field, and the builder is not allowed to know any field's name.
     */
    default Optional<ResolvedValue> bareTerm(String text) {
        return Optional.empty();
    }

    /**
     * What kind of thing the value is, in the words an error message uses: "a date", "a
     * stage". Reads straight into "since: needs a date".
     */
    String valueKind();

    /**
     * Values worth showing her, used for the missing-value message and for autocomplete in
     * file 08. First one wins as the example in an error.
     */
    List<String> examples();

    /**
     * Fields that modify this one rather than filtering on their own, like {@code since}
     * on {@code moved_to}. Declared here rather than known by the validator, which is what
     * keeps a cross-field relationship from becoming a field name in shared code.
     */
    default Set<String> modifiers() {
        return Set.of();
    }

    /** Folds the modifiers that were found alongside this predicate into its value. */
    default ResolvedValue attach(ResolvedValue resolved, Map<String, ResolvedValue> modifiers) {
        return resolved;
    }
}
