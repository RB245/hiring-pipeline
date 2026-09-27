package com.pipeline.application;

import com.pipeline.domain.Candidate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Sweeps every candidate in the job. Reports how many actually differed from their log,
 * because "how much drift is there" is the only question this endpoint exists to answer.
 *
 * <p>Deliberately not one big transaction: a sweep that fails on candidate 300 should
 * leave the first 299 repaired.
 */
@Service
public class RebuildAllProjections {

    private final CandidateReader candidates;
    private final RebuildCandidateProjection rebuild;
    private final JobReader jobs;

    public RebuildAllProjections(
            CandidateReader candidates, RebuildCandidateProjection rebuild, JobReader jobs) {
        this.candidates = candidates;
        this.rebuild = rebuild;
        this.jobs = jobs;
    }

    public Result rebuildAll() {
        UUID jobId = jobs.singleJobId().orElseThrow(NoJobConfiguredException::new);
        List<UUID> ids = candidates.allIds(jobId);

        int changed = 0;
        for (UUID id : ids) {
            Candidate before = candidates.load(id).orElseThrow(() -> new CandidateNotFoundException(id));
            if (!rebuild.rebuild(id).equals(before)) {
                changed++;
            }
        }
        return new Result(ids.size(), changed);
    }

    public record Result(int rebuilt, int changed) {}
}
