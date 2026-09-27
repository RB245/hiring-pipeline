package com.pipeline.application;

import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageEvent;
import com.pipeline.domain.StageTransitions;
import com.pipeline.domain.TransitionDecision;
import java.util.Optional;
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
    private final EventReader events;
    private final StageTransitions transitions;

    public TransitionCandidate(
            CandidateReader reader, CandidateWriter writer, EventReader events, StageTransitions transitions) {
        this.reader = reader;
        this.writer = writer;
        this.events = events;
        this.transitions = transitions;
    }

    /**
     * @param expectedCurrentStage where the caller last saw the candidate; a mismatch
     *     means they are acting on a stale view and nothing is written
     * @throws com.pipeline.domain.IllegalStageTransitionException if the move is not
     *     permitted, carrying the moves that would have been
     */
    @Transactional
    public TransitionOutcome transition(
            UUID candidateId,
            Stage expectedCurrentStage,
            Stage target,
            Actor actor,
            String reason,
            String idempotencyKey) {

        // Checked first and inside the transaction, so a retry that arrives after the
        // original committed sees it. Two genuinely simultaneous requests with one key
        // are a different case: the unique index rejects the loser, and its next retry
        // lands here and gets the original.
        if (idempotencyKey != null) {
            Optional<StageEvent> already = events.findByIdempotencyKey(candidateId, idempotencyKey);
            if (already.isPresent()) {
                return new TransitionOutcome(already.get(), true);
            }
        }

        Candidate candidate = reader.load(candidateId).orElseThrow(() -> new CandidateNotFoundException(candidateId));

        if (expectedCurrentStage != null && candidate.currentStage() != expectedCurrentStage) {
            throw new StaleCandidateStateException(candidateId, expectedCurrentStage, candidate.currentStage());
        }

        TransitionDecision decision = transitions.transition(candidate, target, actor, reason, idempotencyKey);

        writer.appendEvent(decision.event());
        writer.updateProjection(
                candidateId, decision.newStage(), decision.event().occurredAt(), decision.reachedMask());

        return new TransitionOutcome(decision.event(), false);
    }
}
