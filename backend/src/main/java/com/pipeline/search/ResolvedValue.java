package com.pipeline.search;

import com.pipeline.domain.Stage;
import java.time.Instant;
import java.util.Optional;

/**
 * What a predicate's value turned out to mean, once the field that owns it has had a look.
 * Sealed so that file 08's builder is a total switch: a new shape of value is a compile
 * error there rather than a silently unhandled filter.
 *
 * <p>Relative forms are already resolved. {@code >7d} is an instant computed from the
 * injected clock, not a duration to be applied later, because "seven days ago" has to mean
 * the same thing for the whole query however long the query takes to run.
 */
public sealed interface ResolvedValue {

    record StageValue(Stage stage) implements ResolvedValue {}

    record StatusValue(Status status) implements ResolvedValue {}

    /**
     * Fuzzy text. {@code alsoEmail} is the whole difference between a bare word, which
     * identifies a person by any of the ways she is written down, and {@code name:},
     * which means the name and only the name.
     */
    record TextValue(String text, boolean alsoEmail) implements ResolvedValue {}

    /** The literal is kept alongside the instant so {@code /explain} can show its working. */
    record DateValue(String literal, Instant instant) implements ResolvedValue {}

    /**
     * An age comparison. The operator applies to how old the thing is, not to the
     * timestamp, so {@code >7d} is everyone whose timestamp is older than the threshold
     * and {@code <30d} is everyone whose timestamp is newer than it.
     */
    record AgeValue(Operator operator, String literal, Instant threshold) implements ResolvedValue {}

    /** {@code moved_to} with whichever of its {@code since}/{@code before} modifiers were present. */
    record MovedToValue(Stage stage, Optional<DateValue> since, Optional<DateValue> before)
            implements ResolvedValue {}
}
