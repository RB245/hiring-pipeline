package com.pipeline.search.fields;

import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;

/**
 * The comparison that reads backwards, in the one place the two fields that need it can
 * share it.
 *
 * <p>The operator applies to how old the thing is, not to its timestamp, so {@code >7d} is
 * a timestamp <em>before</em> the threshold and {@code <30d} is one after it. Inverting
 * that is the easy mistake and it would answer "who is stuck" with the newest candidates
 * in the pipeline — plausibly, and therefore invisibly.
 */
final class Ages {

    static Predicate matching(ResolvedValue.AgeValue age, jakarta.persistence.criteria.Expression<Instant> timestamp,
            CriteriaBuilder builder) {
        Instant threshold = age.threshold();
        return switch (age.operator()) {
            case GREATER_THAN -> builder.lessThan(timestamp, threshold);
            case GREATER_OR_EQUAL -> builder.lessThanOrEqualTo(timestamp, threshold);
            case LESS_THAN -> builder.greaterThan(timestamp, threshold);
            case LESS_OR_EQUAL -> builder.greaterThanOrEqualTo(timestamp, threshold);
            // Both handlers rewrite a bare "7d" into a comparison while resolving, because
            // nobody means "in this stage for exactly seven days to the second".
            case EQUALS -> throw new IllegalStateException(
                    "An age was resolved without a comparison: " + age.literal());
        };
    }

    private Ages() {}
}
