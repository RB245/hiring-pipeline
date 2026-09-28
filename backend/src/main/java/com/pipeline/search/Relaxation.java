package com.pipeline.search;

/**
 * One way of loosening a query that found nobody: the condition to drop, spelled the way
 * she wrote it, and the query that is left without it.
 *
 * <p>{@code dropped} is rendered from the tree rather than sliced out of her input, so it
 * comes back in the same canonical form {@code /explain} shows — which means the
 * suggestion is something she can paste back into the box.
 */
public record Relaxation(String dropped, Node remainder) {}
