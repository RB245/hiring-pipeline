package com.pipeline.application;

import com.pipeline.domain.Candidate;
import com.pipeline.domain.StageEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes the three denormalised columns from the event log and nothing else. This
 * is what makes denormalising them defensible: the columns are a cache of an immutable
 * log, so drift is always repairable rather than merely unlikely.
 */
@Service
public class RebuildCandidateProjection {

    private final EventReader events;
    private final CandidateWriter writer;

    public RebuildCandidateProjection(EventReader events, CandidateWriter writer) {
        this.events = events;
        this.writer = writer;
    }

    @Transactional
    public Candidate rebuild(UUID candidateId) {
        List<StageEvent> history = events.timeline(candidateId);
        if (history.isEmpty()) {
            throw new CandidateNotFoundException(candidateId);
        }

        Candidate rebuilt = Candidate.replay(candidateId, history);
        writer.updateProjection(
                candidateId, rebuilt.currentStage(), rebuilt.currentStageSince(), rebuilt.reachedMask());
        return rebuilt;
    }
}
