package com.pipeline.application;

import java.time.Duration;

/**
 * A query that was allowed to be abandoned, and was.
 *
 * <p>Never reaches the recruiter. It means an optional extra — today, the edit-distance
 * retry behind a zero-result suggestion — spent its budget, so that suggestion is dropped
 * and the response goes out without it. Typed rather than caught as "some database error",
 * so that a genuine failure is not quietly swallowed as a timeout.
 */
public class SearchBudgetExceededException extends RuntimeException {

    public SearchBudgetExceededException(Duration budget, Throwable cause) {
        super("Gave up after " + budget.toMillis() + "ms", cause);
    }
}
