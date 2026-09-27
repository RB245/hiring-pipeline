package com.pipeline.domain;

import java.util.Optional;

/**
 * The pipeline, in order. Declaration order is the pipeline order, and the successor of
 * a stage is simply the next one declared, so inserting a stage mid-pipeline rewires the
 * chain without touching any other constant.
 *
 * <p>The last stage declared must be terminal, or {@link #next()} would run off the end.
 */
public enum Stage {
    APPLIED(1, false, EventType.APPLIED),
    SCREENING(2, false, EventType.ADVANCED),
    INTERVIEW(4, false, EventType.ADVANCED),
    OFFER(8, false, EventType.ADVANCED),
    HIRED(16, true, EventType.HIRED),
    REJECTED(32, true, EventType.REJECTED);

    private final int bit;
    private final boolean terminal;
    private final EventType entryEventType;

    Stage(int bit, boolean terminal, EventType entryEventType) {
        this.bit = bit;
        this.terminal = terminal;
        this.entryEventType = entryEventType;
    }

    /** Empty for a terminal stage: there is nowhere to advance to. */
    public Optional<Stage> next() {
        return terminal ? Optional.empty() : Optional.of(values()[ordinal() + 1]);
    }

    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Position in the reached-mask. Declared rather than derived from the ordinal,
     * because deriving it would silently renumber every later stage — and so invalidate
     * every mask already stored — the first time someone inserts a stage mid-pipeline.
     */
    public int bit() {
        return bit;
    }

    /**
     * The kind of event recorded by entering this stage. Lives here rather than in the
     * rules so that no rule has to special-case a particular stage.
     */
    public EventType entryEventType() {
        return entryEventType;
    }
}
