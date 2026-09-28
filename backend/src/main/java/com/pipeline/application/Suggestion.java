package com.pipeline.application;

/**
 * Something to try instead, offered when a search came back empty.
 *
 * <p>{@code query} is a complete, canonical DSL string that returns exactly {@code results}
 * candidates — not a hint, not a condition to strip. That is deliberate and it is the whole
 * interface: a client puts it in the search box and resubmits, and does the same thing
 * whether the suggestion was "drop this condition" or "spell that name more loosely". It
 * never has to know which kind it received, and there is no string handling on the way.
 *
 * <p>Because the count is taken from that same query, the promise and the delivery cannot
 * drift. A test asserts exactly that, for both kinds.
 */
public record Suggestion(String label, String query, long results) {}
