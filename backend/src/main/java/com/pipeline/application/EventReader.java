package com.pipeline.application;

import com.pipeline.domain.StageEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventReader {

    /**
     * Ascending by seq. The same ordering the projection rebuild depends on, which is
     * why there is one method and not two.
     */
    List<StageEvent> timeline(UUID candidateId);

    /**
     * Backs idempotent retries. The unique index from file 02 guarantees at most one
     * match, so a replay can return the original event rather than writing a second.
     */
    Optional<StageEvent> findByIdempotencyKey(UUID candidateId, String idempotencyKey);
}
