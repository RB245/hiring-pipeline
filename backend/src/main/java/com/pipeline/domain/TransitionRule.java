package com.pipeline.domain;

/**
 * One kind of legal move. Deliberately not sealed: {@link TransitionRules} composes
 * rules from a list so that a new kind of move is a new rule rather than an edit to an
 * existing one, and sealing would have made that impossible outside this one file.
 */
public interface TransitionRule {
    boolean allows(Stage from, Stage to);
}
