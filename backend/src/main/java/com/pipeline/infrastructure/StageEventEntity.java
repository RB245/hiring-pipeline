package com.pipeline.infrastructure;

import com.pipeline.domain.EventType;
import com.pipeline.domain.Stage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable so Hibernate never generates an UPDATE for it. That is a fourth line of
 * defence rather than the real one: the V6 trigger and the V7 revoke both stop a write
 * this annotation merely never attempts.
 */
@Entity
@Table(name = "stage_event")
@Immutable
class StageEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    UUID candidateId;
    int seq;

    /** Null only on the first event; the V4 check constraint enforces exactly that. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "stage")
    Stage fromStage;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "stage")
    Stage toStage;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "event_type")
    EventType eventType;

    Instant occurredAt;
    String actorId;
    String actorName;
    String reason;
    String idempotencyKey;

    protected StageEventEntity() {}

    StageEventEntity(
            UUID candidateId,
            int seq,
            Stage fromStage,
            Stage toStage,
            EventType eventType,
            Instant occurredAt,
            String actorId,
            String actorName,
            String reason,
            String idempotencyKey) {
        this.candidateId = candidateId;
        this.seq = seq;
        this.fromStage = fromStage;
        this.toStage = toStage;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.actorId = actorId;
        this.actorName = actorName;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
    }
}
