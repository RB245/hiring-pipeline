package com.pipeline.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CandidateJpaRepository extends JpaRepository<CandidateEntity, UUID> {

    List<CandidateEntity> findByJobIdOrderByCreatedAtDescIdDesc(UUID jobId);

    @Query("select c.id from CandidateEntity c where c.jobId = :jobId order by c.createdAt")
    List<UUID> idsForJob(UUID jobId);

    @Query("""
            select c from CandidateEntity c
            where c.jobId = :jobId
            order by c.createdAt desc, c.id desc
            """)
    List<CandidateEntity> firstPage(UUID jobId, Pageable pageable);

    /**
     * Keyset, not OFFSET: the page is defined by where the last one ended, so rows
     * inserted in between cannot shift a later page onto rows already returned. Written
     * out longhand because JPQL has no row-value comparison.
     */
    @Query("""
            select c from CandidateEntity c
            where c.jobId = :jobId
              and (c.createdAt < :createdAt or (c.createdAt = :createdAt and c.id < :id))
            order by c.createdAt desc, c.id desc
            """)
    List<CandidateEntity> pageAfter(UUID jobId, Instant createdAt, UUID id, Pageable pageable);
}
