package com.pipeline.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Events are stamped at microsecond resolution, which is the resolution the log is kept
 * at. Recording anything finer would be a lie the moment it is written down: the store
 * rounds to the nearest microsecond, so an event read back would not equal the one just
 * created, and a replayed idempotent request would return a different body from the
 * original it is supposed to be indistinguishable from.
 *
 * <p>Truncating here rather than in the Clock bean means it holds for every clock,
 * including the fixed and mutable ones the tests inject.
 */
final class EventTime {

    static Instant stamp(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private EventTime() {}
}
