package com.pipeline.infrastructure;

/**
 * Tiered by what a request costs rather than by one global number, because a board
 * refresh and a transition are not the same kind of load and throttling them together
 * means throttling the cheap one to protect the expensive one.
 */
public enum RateLimitTier {
    /** Anything that mutates pipeline state. */
    WRITE,
    /** Board, list, detail, timeline. */
    READ,
    /** Anything that parses a query and scans for matches: /search/*, and the list with a q=. */
    SEARCH
}
