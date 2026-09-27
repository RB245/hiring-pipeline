package com.pipeline.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job")
class JobEntity {

    @Id
    UUID id;

    String title;
    Instant createdAt;

    protected JobEntity() {}
}
