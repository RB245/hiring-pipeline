package com.pipeline.search;

import com.pipeline.domain.Stage;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Stage names as the recruiter writes them. Shared by every field that takes one, so a
 * misspelling is reported the same way whichever field she misspelled it in.
 */
public final class Stages {

    public static Stage resolve(Node.Value value) {
        return Arrays.stream(Stage.values())
                .filter(stage -> stage.name().equalsIgnoreCase(value.text()))
                .findFirst()
                .orElseThrow(() -> new SearchQueryException(ErrorCode.UNKNOWN_STAGE,
                        "There is no stage called \"" + value.text() + "\".",
                        value.span(), value.source(), Levenshtein.closest(value.text(), displayNames())));
    }

    /** Canonical DSL form, which is what autocomplete should offer and examples should use. */
    public static List<String> names() {
        return Arrays.stream(Stage.values()).map(stage -> stage.name().toLowerCase(Locale.ROOT)).toList();
    }

    /** Title case, because that is how the board labels a stage and how a suggestion should read. */
    public static List<String> displayNames() {
        return Arrays.stream(Stage.values())
                .map(stage -> stage.name().charAt(0) + stage.name().substring(1).toLowerCase(Locale.ROOT))
                .toList();
    }

    private Stages() {}
}
