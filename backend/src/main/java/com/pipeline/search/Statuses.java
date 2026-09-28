package com.pipeline.search;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** {@link Stages}, for the other closed vocabulary the search box has. */
public final class Statuses {

    public static Status resolve(Node.Value value) {
        return Arrays.stream(Status.values())
                .filter(status -> status.name().equalsIgnoreCase(value.text()))
                .findFirst()
                .orElseThrow(() -> new SearchQueryException(ErrorCode.UNKNOWN_STATUS,
                        "There is no status called \"" + value.text() + "\". Try " + String.join(", ", names()) + ".",
                        value.span(), value.source(), Levenshtein.closest(value.text(), names())));
    }

    public static List<String> names() {
        return Arrays.stream(Status.values()).map(status -> status.name().toLowerCase(Locale.ROOT)).toList();
    }

    private Statuses() {}
}
