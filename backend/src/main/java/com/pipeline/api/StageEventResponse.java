package com.pipeline.api;

import com.pipeline.domain.EventType;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The transition response is the event, not the candidate. A replayed request has to
 * return exactly what the original did, and the candidate's live state can have moved
 * on since; the event cannot.
 */
public record StageEventResponse(
        UUID candidateId,
        Stage fromStage,
        Stage toStage,
        EventType eventType,
        Instant occurredAt,
        String actorId,
        String actorName,
        String reason) {

    static StageEventResponse of(StageEvent event) {
        return new StageEventResponse(
                event.candidateId(),
                event.fromStage(),
                event.toStage(),
                event.eventType(),
                event.occurredAt(),
                event.actor().id(),
                event.actor().name(),
                event.reason());
    }
}
