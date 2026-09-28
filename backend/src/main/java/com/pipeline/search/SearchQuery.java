package com.pipeline.search;

/**
 * A query that parsed and means something: what she typed, what it was read as, and the
 * tree file 08 turns into SQL. Only ever built from a validated AST, which is why nothing
 * downstream has to check whether a value was resolved.
 */
public record SearchQuery(String raw, String dsl, Node ast) {}
