package com.pipeline.search;

/**
 * One way of loosening a query that found nobody: what to call it, and the query that does
 * it.
 *
 * <p>Both are strings in the canonical DSL, and {@code query} is a complete one rather than
 * the condition to remove. That is what lets the caller take the count from the very query
 * it offers, instead of counting one thing and advertising another and trusting the two to
 * stay in step. It also means a client does one thing with a suggestion whatever kind it
 * is: put {@code query} in the box and resubmit.
 */
public record Relaxation(String label, String query) {}
