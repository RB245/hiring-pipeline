package com.pipeline.application;

import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageTransitions;
import com.pipeline.domain.TransitionDecision;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only thing that moves a candidate through the pipeline, and it does so in one
 * transaction: load, ask the domain, append exactly one event, update the projection.
 * A failure anywhere leaves none of it behind.
 *
 * <p>The load is what arms optimistic locking. The entity stays managed for the rest of
 * the transaction, so a concurrent transition that committed in between makes the
 * projection update fail at flush rather than silently overwrite.
 */
@Service
public class TransitionCandidate {

    private final CandidateReader reader;
    private final CandidateWriter writer;
    private final StageTransitions transitions;

    public TransitionCandidate(CandidateReader reader, CandidateWriter writer, StageTransitions transitions) {
        this.reader = reader;
        this.writer = writer;
        this.transitions = transitions;
    }

    /**
     * @throws com.pipeline.domain.IllegalStageTransitionException if the move is not
     *     permitted, carrying the moves that would have been
     */
    @Transactional
    public TransitionDecision transition(
            UUID candidateId, Stage target, Actor actor, String reason, String idempotencyKey) {

        Candidate candidate = reader.load(candidateId).orElseThrow(() -> new CandidateNotFoundException(candidateId));

        TransitionDecision decision = transitions.transition(candidate, target, actor, reason, idempotencyKey);

        writer.appendEvent(decision.event());
        writer.updateProjection(
                candidateId, decision.newStage(), decision.event().occurredAt(), decision.reachedMask());

        return decision;
    }
}
