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
     * Fuzzy text, and how forgivingly it is read. Three shapes rather than a pair of
     * booleans, because they are three ways of asking the same question and {@code
     * /explain} has to be able to say which one she asked.
     */
    record TextValue(String text, Match match) implements ResolvedValue {

        public enum Match {
            /** {@code name:} — the name, and only the name. */
            NAME,
            /** A bare word: any of the ways a person is written down, so the email too. */
            IDENTITY,
            /** {@code name_like:} — identity, plus names within an edit or two of it. */
            LOOSE
        }

        /** The email is matched for everything except a question explicitly about the name. */
        public boolean includesEmail() {
            return match != Match.NAME;
        }
    }

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
