package com.pipeline.infrastructure;

import com.pipeline.application.EventReader;
import com.pipeline.domain.Actor;
import com.pipeline.domain.StageEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class JpaEventReader implements EventReader {

    private final StageEventJpaRepository events;

    JpaEventReader(StageEventJpaRepository events) {
        this.events = events;
    }

    @Override
    public Optional<StageEvent> findByIdempotencyKey(UUID candidateId, String idempotencyKey) {
        return events.findByCandidateIdAndIdempotencyKey(candidateId, idempotencyKey)
                .map(JpaEventReader::toDomain);
    }

    @Override
    public List<StageEvent> timeline(UUID candidateId) {
        return events.findByCandidateIdOrderBySeqAsc(candidateId).stream()
                .map(JpaEventReader::toDomain)
                .toList();
    }

    private static StageEvent toDomain(StageEventEntity entity) {
        return new StageEvent(
                entity.candidateId,
                entity.fromStage,
                entity.toStage,
                entity.eventType,
                entity.occurredAt,
                new Actor(entity.actorId, entity.actorName),
                entity.reason,
                entity.idempotencyKey);
    }
}
