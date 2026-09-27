package com.pipeline.infrastructure;

import com.pipeline.application.CandidateNotFoundException;
import com.pipeline.application.CandidateProfile;
import com.pipeline.application.CandidateWriter;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class JpaCandidateWriter implements CandidateWriter {

    private final CandidateJpaRepository candidates;
    private final StageEventJpaRepository events;

    JpaCandidateWriter(CandidateJpaRepository candidates, StageEventJpaRepository events) {
        this.candidates = candidates;
        this.events = events;
    }

    @Override
    public void insertCandidate(CandidateProfile profile, Candidate candidate) {
        CandidateEntity entity = new CandidateEntity(
                candidate.id(),
                profile.jobId(),
                profile.fullName(),
                profile.email(),
                profile.phone(),
                profile.source(),
                candidate.currentStage(),
                candidate.currentStageSince(),
                (short) candidate.reachedMask(),
                // created_at and the first event's occurredAt are the same instant by
                // construction, so the row and its log cannot disagree about when the
                // candidate appeared.
                candidate.currentStageSince());

        // Flushed rather than queued: stage_event's id is IDENTITY-generated, so
        // persisting an event executes its INSERT immediately, and the candidate row it
        // references has to already be there.
        candidates.saveAndFlush(entity);
    }

    @Override
    public void appendEvent(StageEvent event) {
        int seq = events.highestSeq(event.candidateId()) + 1;
        events.save(new StageEventEntity(
                event.candidateId(),
                seq,
                event.fromStage(),
                event.toStage(),
                event.eventType(),
                event.occurredAt(),
                event.actor().id(),
                event.actor().name(),
                event.reason(),
                event.idempotencyKey()));
    }

    @Override
    public void updateProjection(UUID candidateId, Stage stage, Instant since, int reachedMask) {
        // Within a transaction that already loaded this candidate, findById returns the
        // same managed instance, so the version read at load time is the one checked at
        // flush. That is what makes a concurrent transition fail rather than overwrite.
        CandidateEntity entity =
                candidates.findById(candidateId).orElseThrow(() -> new CandidateNotFoundException(candidateId));
        entity.currentStage = stage;
        entity.currentStageSince = since;
        entity.reachedMask = (short) reachedMask;
    }
}
