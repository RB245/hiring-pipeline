package com.pipeline.infrastructure;

import com.pipeline.domain.Stage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * is_terminal is deliberately unmapped: it is a generated column and the database owns
 * it. Mapping it would invite Hibernate to try to write it.
 */
@Entity
@Table(name = "candidate")
class CandidateEntity {

    @Id
    UUID id;

    UUID jobId;
    String fullName;

    // columnDefinition is what makes ddl-auto=validate accept these: Hibernate
    // otherwise expects varchar for a String and derives "eventtype" for the enum
    // below, neither of which is what file 02 created.
    @Column(columnDefinition = "citext")
    String email;
    String phone;
    String source;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "stage")
    Stage currentStage;

    Instant currentStageSince;
    short reachedMask;
    Instant createdAt;

    @Version
    int version;

    protected CandidateEntity() {}

    CandidateEntity(
            UUID id,
            UUID jobId,
            String fullName,
            String email,
            String phone,
            String source,
            Stage currentStage,
            Instant currentStageSince,
            short reachedMask,
            Instant createdAt) {
        this.id = id;
        this.jobId = jobId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.source = source;
        this.currentStage = currentStage;
        this.currentStageSince = currentStageSince;
        this.reachedMask = reachedMask;
        this.createdAt = createdAt;
    }
}
