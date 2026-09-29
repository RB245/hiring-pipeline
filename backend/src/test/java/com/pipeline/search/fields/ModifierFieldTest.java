package com.pipeline.search.fields;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipeline.search.ResolvedValue;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The invariant the two modifier fields rely on: the validator folds {@code since} and
 * {@code before} into the {@code moved_to} beside them and rejects one with nothing to
 * modify, so neither ever reaches the builder as a condition of its own.
 *
 * <p>Asserted rather than assumed because of what the alternative would look like. If that
 * folding ever broke, a handler returning some harmless predicate would turn "moved to
 * Interview since Monday" into two conditions, the second matching everybody — a query
 * that ran, returned rows, and quietly ignored half of what she asked for. Throwing makes
 * that a failure instead of a wrong answer, and this is what proves it still throws.
 */
class ModifierFieldTest {

    private static final ResolvedValue DATE = new ResolvedValue.DateValue("monday", Instant.EPOCH);

    @Test
    void sinceHasNoPredicateOfItsOwn() {
        assertThatThrownBy(() -> new SinceField().predicate(DATE, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("since: is a modifier");
    }

    @Test
    void norDoesBefore() {
        assertThatThrownBy(() -> new BeforeField().predicate(DATE, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before: is a modifier");
    }
}
