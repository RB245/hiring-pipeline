package com.pipeline.application;

import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.CandidateCreation;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a candidate row and its first event are written, and the one place
 * {@code from_stage IS NULL} with {@code seq = 1} is correct. Both writes or neither.
 */
@Service
public class RegisterCandidate {

    private final CandidateWriter writer;
    private final Clock clock;

    public RegisterCandidate(CandidateWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    @Transactional
    public UUID register(CandidateProfile profile, Actor actor) {
        CandidateCreation creation = Candidate.register(UUID.randomUUID(), actor, clock);
        writer.insertCandidate(profile, creation.candidate());
        writer.appendEvent(creation.firstEvent());
        return creation.candidate().id();
    }
}
