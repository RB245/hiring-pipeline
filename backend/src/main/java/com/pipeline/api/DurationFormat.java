package com.pipeline.api;

import java.time.Duration;

/**
 * The recruiter reads "6 days", not PT144H. Coarse on purpose: the board asks how long
 * someone has been sitting somewhere, and a minute of precision on a week-old figure
 * would be noise.
 */
final class DurationFormat {

    static String humanise(Duration duration) {
        if (duration.isNegative()) {
            return "just now";
        }
        long days = duration.toDays();
        if (days > 0) {
            return plural(days, "day");
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return plural(hours, "hour");
        }
        long minutes = duration.toMinutes();
        if (minutes > 0) {
            return plural(minutes, "minute");
        }
        return "just now";
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }

    private DurationFormat() {}
}
