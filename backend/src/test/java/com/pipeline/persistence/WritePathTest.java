package com.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import com.pipeline.application.CandidateProfile;
import com.pipeline.application.CandidateReader;
import com.pipeline.application.CandidateWriter;
import com.pipeline.application.EventReader;
import com.pipeline.application.RegisterCandidate;
import com.pipeline.application.TransitionCandidate;
import com.pipeline.db.SchemaFixture;
import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.EventType;
import com.pipeline.domain.IllegalStageTransitionException;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

class WritePathTest extends PersistenceTest {

    private static final Actor ACTOR = new Actor("recruiter-1", "Asha");

    @Autowired RegisterCandidate registerCandidate;
    @Autowired TransitionCandidate transitionCandidate;
    @Autowired CandidateReader reader;
    @Autowired EventReader events;
    @Autowired MutableClock clock;
    @Autowired TransactionTemplate transactions;

    @MockitoSpyBean CandidateWriter writer;

    private UUID jobId;

    @BeforeEach
    void setUp() throws SQLException {
        jobId = createJob();
    }

    private UUID register() {
        return registerCandidate.register(
                new CandidateProfile(jobId, "Priya Sharma", UUID.randomUUID() + "@example.com", null, "referral"),
                ACTOR);
    }

    @Test
    void registrationWritesTheCandidateAndItsFirstEventTogether() throws SQLException {
        UUID id = register();

        assertThat(candidateExists(id)).isTrue();
        List<StageEvent> timeline = events.timeline(id);
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).fromStage()).isNull();
        assertThat(timeline.get(0).toStage()).isEqualTo(Stage.APPLIED);
        assertThat(timeline.get(0).eventType()).isEqualTo(EventType.APPLIED);
    }

    @Test
    void aTransitionWritesExactlyOneEventAndAllThreeProjectionColumns() {
        UUID id = register();
        clock.advance(Duration.ofDays(2));

        transitionCandidate.transition(id, Stage.SCREENING, ACTOR, "good CV", "key-1");

        assertThat(events.timeline(id)).hasSize(2);
        Candidate loaded = reader.load(id).orElseThrow();
        assertThat(loaded.currentStage()).isEqualTo(Stage.SCREENING);
        assertThat(loaded.currentStageSince()).isEqualTo(clock.instant());
        assertThat(loaded.reachedMask()).isEqualTo(Stage.APPLIED.bit() | Stage.SCREENING.bit());
    }

    @Test
    void anIllegalTransitionWritesNothing() {
        UUID id = register();

        assertThatExceptionOfType(IllegalStageTransitionException.class)
                .isThrownBy(() -> transitionCandidate.transition(id, Stage.OFFER, ACTOR, null, null));

        assertThat(events.timeline(id)).hasSize(1);
        assertThat(reader.load(id).orElseThrow().currentStage()).isEqualTo(Stage.APPLIED);
    }

    /** Step 4 fails. The event appended at step 3 must not survive it. */
    @Test
    void aFailureUpdatingTheProjectionLeavesNoOrphanedEvent() {
        UUID id = register();
        doThrow(new RuntimeException("step 4 exploded"))
                .when(writer)
                .updateProjection(any(), any(), any(), anyInt());

        assertThatThrownBy(() -> transitionCandidate.transition(id, Stage.SCREENING, ACTOR, null, null))
                .hasMessage("step 4 exploded");

        assertThat(events.timeline(id)).hasSize(1);
        assertThat(reader.load(id).orElseThrow().currentStage()).isEqualTo(Stage.APPLIED);
    }

    /**
     * The candidate row is flushed before the event is appended, so only the rollback
     * can undo it. That is precisely what makes this worth asserting.
     */
    @Test
    void aFailureAppendingTheFirstEventLeavesNoCandidate() throws SQLException {
        doThrow(new RuntimeException("first event exploded")).when(writer).appendEvent(any());

        assertThatThrownBy(this::register).hasMessage("first event exploded");

        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement = owner.prepareStatement(
                        "SELECT count(*) FROM candidate WHERE job_id = ?")) {
            statement.setObject(1, jobId);
            try (var rs = statement.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    /**
     * The file 02 check constraint reached through the write path: a second originless
     * event would land on seq 2, where from_stage may not be null.
     */
    @Test
    void aCandidateCannotBeRegisteredTwice() {
        UUID id = register();

        assertThatThrownBy(() -> writeSecondOriginlessEvent(id))
                .rootCause()
                .hasMessageContaining("stage_event_first_has_no_from");

        assertThat(events.timeline(id)).hasSize(1);
    }

    private void writeSecondOriginlessEvent(UUID id) {
        transactions.executeWithoutResult(status -> writer.appendEvent(
                new StageEvent(id, null, Stage.APPLIED, EventType.APPLIED, clock.instant(), ACTOR, null, null)));
    }

    /** Why candidate.version exists: the loser of a concurrent transition must not win. */
    @Test
    void aConcurrentUpdateMakesTheTransitionFail() {
        UUID id = register();

        assertThatExceptionOfType(OptimisticLockingFailureException.class).isThrownBy(() ->
                transactions.executeWithoutResult(status -> {
                    Candidate candidate = reader.load(id).orElseThrow();
                    bumpVersionOutsideThisTransaction(id);
                    writer.updateProjection(
                            id, Stage.SCREENING, clock.instant(), candidate.reachedMask() | Stage.SCREENING.bit());
                }));

        assertThat(reader.load(id).orElseThrow().currentStage()).isEqualTo(Stage.APPLIED);
    }

    private void bumpVersionOutsideThisTransaction(UUID id) {
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement =
                        owner.prepareStatement("UPDATE candidate SET version = version + 1 WHERE id = ?")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
