package com.pipeline.search;

/**
 * How a predicate compares. The written grammar puts the comparison between the field and
 * the value ({@code in_stage_for>7d}) while every query the recruiter actually types puts
 * it after the colon ({@code in_stage_for:>7d}). Both forms fold to the same operator
 * here, and {@link #render()} always emits the second, so the canonical DSL matches what
 * she sees in the box.
 */
public enum Operator {
    EQUALS(""),
    GREATER_THAN(">"),
    GREATER_OR_EQUAL(">="),
    LESS_THAN("<"),
    LESS_OR_EQUAL("<=");

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    /** What follows the colon in canonical form; empty for equality, which needs no marker. */
    public String render() {
        return symbol;
    }

    public boolean isComparison() {
        return this != EQUALS;
    }
}
