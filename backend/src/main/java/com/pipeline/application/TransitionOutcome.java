package com.pipeline.application;

import com.pipeline.domain.StageEvent;

/**
 * {@code replayed} is true when an idempotency key matched an event already recorded.
 * The event itself is identical either way, which is the point.
 */
public record TransitionOutcome(StageEvent event, boolean replayed) {}
