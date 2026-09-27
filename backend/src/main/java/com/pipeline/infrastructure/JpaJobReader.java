package com.pipeline.infrastructure;

import com.pipeline.application.JobReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
class JpaJobReader implements JobReader {

    private final JobJpaRepository jobs;

    JpaJobReader(JobJpaRepository jobs) {
        this.jobs = jobs;
    }

    /**
     * Ordered, not merely limited. Without an ORDER BY, Postgres is free to return a
     * different row each call, and "the" job opening would quietly stop being one job.
     */
    @Override
    public Optional<UUID> singleJobId() {
        List<JobEntity> found = jobs
                .findAll(PageRequest.ofSize(1).withSort(Sort.by("createdAt").and(Sort.by("id"))))
                .getContent();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0).id);
    }
}
