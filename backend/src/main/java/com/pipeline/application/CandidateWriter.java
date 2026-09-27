package com.pipeline.application;

import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The only way pipeline state changes. Three separate operations rather than one
 * combined call, because the use cases compose them differently: registration is an
 * insert plus a first event, a transition is an event plus a projection update, and a
 * rebuild is a projection update with no event at all.
 */
public interface CandidateWriter {

    void insertCandidate(CandidateProfile profile, Candidate candidate);

    /** Assigns the next seq for this candidate. */
    void appendEvent(StageEvent event);

    void updateProjection(UUID candidateId, Stage stage, Instant since, int reachedMask);
}
