package com.pipeline.application;

import com.pipeline.domain.Stage;
import java.util.UUID;

/**
 * The caller acted on a stale view. Concurrency control, not a pipeline rule, which is
 * why it lives here rather than in the domain: the domain has no opinion about what the
 * caller last saw.
 */
public class StaleCandidateStateException extends RuntimeException {

    private final transient Stage expected;
    private final transient Stage actual;

    public StaleCandidateStateException(UUID candidateId, Stage expected, Stage actual) {
        super("Candidate %s is in %s, not the expected %s".formatted(candidateId, actual, expected));
        this.expected = expected;
        this.actual = actual;
    }

    public Stage expected() {
        return expected;
    }

    public Stage actual() {
        return actual;
    }
}
