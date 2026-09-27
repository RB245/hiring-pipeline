package com.pipeline.domain;

/**
 * Everything the persistence layer needs and nothing about how to persist it. The new
 * {@code current_stage_since} is the event's own {@code occurredAt}, so it is not
 * repeated here.
 */
public record TransitionDecision(Stage newStage, StageEvent event, int reachedMask) {}
