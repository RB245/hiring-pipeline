package com.pipeline.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StageEventJpaRepository extends JpaRepository<StageEventEntity, Long> {

    /**
     * Zero for a candidate with no events yet, so the first event lands on seq 1. The
     * read-then-write race is closed by optimistic locking on the candidate row, with
     * stage_event_candidate_seq_uq as the backstop.
     */
    @Query("select coalesce(max(e.seq), 0) from StageEventEntity e where e.candidateId = :candidateId")
    int highestSeq(UUID candidateId);

    List<StageEventEntity> findByCandidateIdOrderBySeqAsc(UUID candidateId);
}
