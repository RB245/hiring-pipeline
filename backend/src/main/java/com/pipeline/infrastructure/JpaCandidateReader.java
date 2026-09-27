package com.pipeline.infrastructure;

import com.pipeline.application.BoardColumn;
import com.pipeline.application.CandidatePage;
import com.pipeline.application.CandidateReader;
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.Cursor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
class JpaCandidateReader implements CandidateReader {

    private final CandidateJpaRepository candidates;

    JpaCandidateReader(CandidateJpaRepository candidates) {
        this.candidates = candidates;
    }

    @Override
    public Optional<Candidate> load(UUID candidateId) {
        return candidates.findById(candidateId).map(JpaCandidateReader::toDomain);
    }

    @Override
    public Optional<CandidateSummary> summary(UUID candidateId) {
        return candidates.findById(candidateId).map(JpaCandidateReader::toSummary);
    }

    @Override
    public List<UUID> allIds(UUID jobId) {
        return candidates.idsForJob(jobId);
    }

    /**
     * Grouped in memory on purpose. Rendering the board reads every candidate for the
     * job whatever happens, so a GROUP BY plus one query per column would be more round
     * trips for the same rows. Empty columns are included so the board keeps its shape.
     */
    @Override
    public List<BoardColumn> board(UUID jobId) {
        List<CandidateEntity> all = candidates.findByJobIdOrderByCreatedAtDescIdDesc(jobId);
        return Arrays.stream(Stage.values())
                .map(stage -> new BoardColumn(
                        stage,
                        all.stream()
                                .filter(candidate -> candidate.currentStage == stage)
                                .map(JpaCandidateReader::toSummary)
                                .toList()))
                .toList();
    }

    @Override
    public CandidatePage page(UUID jobId, Cursor after, int limit) {
        // One more than asked for, purely to discover whether a next page exists
        // without a second count query.
        PageRequest window = PageRequest.ofSize(limit + 1);
        List<CandidateEntity> rows = after == null
                ? candidates.firstPage(jobId, window)
                : candidates.pageAfter(jobId, after.createdAt(), after.id(), window);

        boolean more = rows.size() > limit;
        List<CandidateSummary> summaries = new ArrayList<>();
        for (CandidateEntity row : rows.subList(0, more ? limit : rows.size())) {
            summaries.add(toSummary(row));
        }

        CandidateEntity last = more ? rows.get(limit - 1) : null;
        return new CandidatePage(summaries, last == null ? null : new Cursor(last.createdAt, last.id));
    }

    private static Candidate toDomain(CandidateEntity entity) {
        return new Candidate(entity.id, entity.currentStage, entity.currentStageSince, entity.reachedMask);
    }

    private static CandidateSummary toSummary(CandidateEntity entity) {
        return new CandidateSummary(
                entity.id,
                entity.fullName,
                entity.email,
                entity.phone,
                entity.source,
                entity.currentStage,
                entity.currentStageSince,
                entity.createdAt);
    }
}
