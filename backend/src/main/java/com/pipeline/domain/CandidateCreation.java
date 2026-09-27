package com.pipeline.domain;

/**
 * What registering a candidate produces. The counterpart to {@link TransitionDecision}:
 * a candidate does not transition into existence, so the first event has no origin
 * stage and no rule governs it.
 */
public record CandidateCreation(Candidate candidate, StageEvent firstEvent) {}
