package com.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.application.CandidateProfile;
import com.pipeline.application.CandidateReader;
import com.pipeline.application.RebuildCandidateProjection;
import com.pipeline.application.RegisterCandidate;
import com.pipeline.application.TransitionCandidate;
import com.pipeline.db.SchemaFixture;
import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.Stage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The claim under test is that the event log is the source of truth and the three
 * denormalised columns are a cache of it. The way to prove that is to break the cache
 * on purpose and show it can be reconstructed from the log alone.
 */
class ProjectionRebuildTest extends PersistenceTest {

    private static final Actor ACTOR = new Actor("recruiter-1", "Asha");

    @Autowired RegisterCandidate registerCandidate;
    @Autowired TransitionCandidate transitionCandidate;
    @Autowired RebuildCandidateProjection rebuild;
    @Autowired CandidateReader reader;
    @Autowired MutableClock clock;

    @Test
    void theProjectionIsReconstructedFromTheLogAlone() throws SQLException {
        UUID jobId = createJob();
        UUID id = registerCandidate.register(
                new CandidateProfile(jobId, "Priya Sharma", "priya@example.com", null, "referral"), ACTOR);

        clock.advance(Duration.ofDays(3));
        transitionCandidate.transition(id, Stage.SCREENING, ACTOR, null, null);
        clock.advance(Duration.ofDays(4));
        transitionCandidate.transition(id, Stage.INTERVIEW, ACTOR, null, null);
        clock.advance(Duration.ofDays(2));
        transitionCandidate.transition(id, Stage.OFFER, ACTOR, null, null);

        Instant truthfulSince = clock.instant();
        int truthfulMask =
                Stage.APPLIED.bit() | Stage.SCREENING.bit() | Stage.INTERVIEW.bit() | Stage.OFFER.bit();

        corruptProjection(id);
        Candidate corrupted = reader.load(id).orElseThrow();
        assertThat(corrupted.currentStage()).isEqualTo(Stage.APPLIED);
        assertThat(corrupted.reachedMask()).isEqualTo(Stage.APPLIED.bit());

        rebuild.rebuild(id);

        Candidate repaired = reader.load(id).orElseThrow();
        assertThat(repaired.currentStage()).isEqualTo(Stage.OFFER);
        assertThat(repaired.currentStageSince()).isEqualTo(truthfulSince);
        assertThat(repaired.reachedMask()).isEqualTo(truthfulMask);
    }

    @Test
    void rebuildingChangesNothingWhenTheProjectionIsAlreadyRight() throws SQLException {
        UUID jobId = createJob();
        UUID id = registerCandidate.register(
                new CandidateProfile(jobId, "Rahul Verma", "rahul@example.com", null, null), ACTOR);
        clock.advance(Duration.ofDays(1));
        transitionCandidate.transition(id, Stage.SCREENING, ACTOR, null, null);

        Candidate before = reader.load(id).orElseThrow();
        rebuild.rebuild(id);

        assertThat(reader.load(id).orElseThrow()).isEqualTo(before);
    }

    /** Straight SQL as the owner, bypassing every use case, exactly as drift would. */
    private static void corruptProjection(UUID candidateId) throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement = owner.prepareStatement(
                        """
                        UPDATE candidate
                           SET current_stage = 'APPLIED',
                               current_stage_since = timestamptz '2000-01-01 00:00:00Z',
                               reached_mask = 1
                         WHERE id = ?
                        """)) {
            statement.setObject(1, candidateId);
            statement.executeUpdate();
        }
    }
}
