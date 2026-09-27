package com.pipeline.domain;

/**
 * Rejection from anywhere still in play. Someone already hired cannot be rejected, and
 * someone already rejected cannot be rejected twice.
 */
public final class RejectRule implements TransitionRule {

    @Override
    public boolean allows(Stage from, Stage to) {
        return to == Stage.REJECTED && !from.isTerminal();
    }
}
