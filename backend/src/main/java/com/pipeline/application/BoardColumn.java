package com.pipeline.application;

import com.pipeline.domain.Stage;
import java.util.List;

/**
 * One column of the board. The count is derived rather than carried, so there is no
 * second number that can disagree with the list it counts.
 */
public record BoardColumn(Stage stage, List<CandidateSummary> candidates) {

    public int count() {
        return candidates.size();
    }
}
