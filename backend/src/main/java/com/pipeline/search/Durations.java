package com.pipeline.search;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations in their canonical form. The word forms — "a week", "more than three days" —
 * have already become {@code 7d} and {@code >3d} in the normaliser, so only the short
 * forms reach here and there is one place that knows what {@code 2w} means.
 */
public final class Durations {

    private static final Pattern DURATION = Pattern.compile("(\\d{1,4})(d|w|mo)");

    /**
     * The instant a candidate's timestamp has to sit on the far side of. Months are
     * calendar months rather than thirty days, because "three months in Screening" is a
     * statement about the calendar; days and weeks keep the time of day, so the answer
     * does not jump at midnight.
     */
    public static Instant threshold(Node.Value value, Clock clock) {
        Matcher matcher = DURATION.matcher(value.text().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new SearchQueryException(ErrorCode.BAD_DURATION,
                    "\"" + value.text() + "\" is not a length of time."
                            + " Try 7d, 2w or 3mo, or write it out as \"a week\" or \"more than 3 days\".",
                    value.span(), value.source());
        }
        int amount = Integer.parseInt(matcher.group(1));
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return switch (matcher.group(2)) {
            case "w" -> now.minusWeeks(amount).toInstant();
            case "mo" -> now.minusMonths(amount).toInstant();
            default -> now.minusDays(amount).toInstant();
        };
    }

    private Durations() {}
}
