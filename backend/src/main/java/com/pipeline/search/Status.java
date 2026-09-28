package com.pipeline.search;

import com.pipeline.domain.Stage;
import java.util.Optional;

/**
 * What {@code status:} means, expressed against the pipeline rather than duplicated from
 * it: the two terminal stages are the two outcomes, and everyone else is still in play.
 * Derived rather than listed, so adding a terminal stage cannot leave this behind.
 */
public enum Status {
    HIRED(Stage.HIRED),
    REJECTED(Stage.REJECTED),
    ACTIVE(null);

    private final Stage terminalStage;

    Status(Stage terminalStage) {
        this.terminalStage = terminalStage;
    }

    /** Empty for {@code active}, which is the absence of a terminal stage rather than one of them. */
    public Optional<Stage> terminalStage() {
        return Optional.ofNullable(terminalStage);
    }
}
