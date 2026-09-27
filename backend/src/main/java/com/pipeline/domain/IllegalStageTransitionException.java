package com.pipeline.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Carries the alternatives that would have been accepted, because both the API and the
 * board need to tell the recruiter what she can do instead, not merely that she cannot
 * do this.
 *
 * <p>Unchecked: a caller attempting an illegal move has a bug or a stale screen, and
 * neither is something every call site should be forced to handle.
 */
public class IllegalStageTransitionException extends RuntimeException {

    private final transient Stage from;
    private final transient Stage to;
    private final transient List<Stage> legalTargets;

    public IllegalStageTransitionException(Stage from, Stage to, List<Stage> legalTargets) {
        super(describe(from, to, legalTargets));
        this.from = from;
        this.to = to;
        this.legalTargets = List.copyOf(legalTargets);
    }

    private static String describe(Stage from, Stage to, List<Stage> legalTargets) {
        if (legalTargets.isEmpty()) {
            return "Cannot move from %s to %s: %s is terminal".formatted(from, to, from);
        }
        return "Cannot move from %s to %s; legal moves from %s are %s"
                .formatted(from, to, from, legalTargets.stream().map(Enum::name).collect(Collectors.joining(", ")));
    }

    public Stage from() {
        return from;
    }

    public Stage to() {
        return to;
    }

    public List<Stage> legalTargets() {
        return legalTargets;
    }
}
