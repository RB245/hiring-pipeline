package com.pipeline.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The event an accepted transition produces. No {@code seq}: the position of this event
 * in the stored log is a property of the log, assigned when it is written, and nothing
 * in the domain reasons about it.
 */
public record StageEvent(
        UUID candidateId,
        Stage fromStage,
        Stage toStage,
        EventType eventType,
        Instant occurredAt,
        Actor actor,
        String reason,
        String idempotencyKey) {}
