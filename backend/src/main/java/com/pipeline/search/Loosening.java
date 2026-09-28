package com.pipeline.search;

/**
 * A condition retried against a more generous field: {@code name:pryia} asked again as
 * {@code name_like:pryia}.
 *
 * <p>Separate from {@link Relaxation} because the two are answered differently. Dropping a
 * condition is cheap and always worth counting; loosening one runs an unindexable
 * edit-distance scan, so it is counted under a time budget and abandoned rather than
 * allowed to hang.
 *
 * <p>{@code asWritten} is the condition she actually typed, kept so the suggestion can be
 * withheld when loosening would find nobody new. Offering "6 results with a looser name
 * match" when the name already matched those six blames her spelling for an empty result
 * some other condition caused.
 */
public record Loosening(String label, String query, String asWritten) {}
