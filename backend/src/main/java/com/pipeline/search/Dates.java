package com.pipeline.search;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Dates in every form the search box accepts, resolved against the injected clock and
 * never the system one — "since Monday" has to mean the same Monday in a test as it does
 * in production.
 *
 * <p>All of it is UTC, which is what the clock bean returns and what the database stores.
 * For a single recruiter that is a trade rather than a bug: asking for "today" shortly
 * after midnight in a far-eastern timezone would answer for yesterday. The fix is a
 * configured display zone, and it belongs with multi-tenancy rather than here.
 */
public final class Dates {

    private static final List<DayOfWeek> WEEKDAYS = Arrays.asList(DayOfWeek.values());

    public static ResolvedValue.DateValue resolve(Node.Value value, Clock clock) {
        String literal = value.text().strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate date = switch (literal) {
            case "today" -> today;
            case "yesterday" -> today.minusDays(1);
            case "this week" -> startOfWeek(today);
            case "last week" -> startOfWeek(today).minusWeeks(1);
            default -> relativeOrIso(literal, today, value);
        };
        return new ResolvedValue.DateValue(literal, date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static LocalDate relativeOrIso(String literal, LocalDate today, Node.Value value) {
        boolean previousWeek = literal.startsWith("last ");
        String bare = previousWeek ? literal.substring("last ".length()) : literal;

        Optional<DayOfWeek> weekday = WEEKDAYS.stream()
                .filter(day -> day.name().equalsIgnoreCase(bare))
                .findFirst();
        if (weekday.isPresent()) {
            // "Monday" is the most recent one, today included, which is how a recruiter
            // saying "moved since Monday" on a Monday means this morning rather than a
            // week ago. "last Monday" is then unambiguously the one before it.
            LocalDate mostRecent = today.with(TemporalAdjusters.previousOrSame(weekday.get()));
            return previousWeek ? mostRecent.minusWeeks(1) : mostRecent;
        }
        if (previousWeek) {
            throw badDate(value);
        }
        try {
            return LocalDate.parse(literal);
        } catch (DateTimeParseException e) {
            throw badDate(value);
        }
    }

    /** ISO weeks, so "this week" starts on Monday whatever the JVM's default locale thinks. */
    private static LocalDate startOfWeek(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static SearchQueryException badDate(Node.Value value) {
        return new SearchQueryException(ErrorCode.BAD_DATE,
                "\"" + value.text() + "\" is not a date."
                        + " Try 2025-03-11, today, yesterday, monday, \"last monday\" or \"this week\".",
                value.span(), value.source());
    }

    private Dates() {}
}
