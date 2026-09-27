package com.pipeline.domain;

/**
 * Exactly one stage forward. Skipping ahead, going back and standing still all fail the
 * same way, and a terminal stage has no successor to match against.
 */
public final class AdvanceRule implements TransitionRule {

    @Override
    public boolean allows(Stage from, Stage to) {
        return from.next().filter(successor -> successor == to).isPresent();
    }
}
