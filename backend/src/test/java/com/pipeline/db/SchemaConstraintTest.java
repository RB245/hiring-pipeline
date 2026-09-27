package com.pipeline.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SchemaConstraintTest {

    private static UUID jobId;

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            jobId = SchemaFixture.insertJob(owner);
        }
    }

    private static UUID freshCandidate(Connection connection) throws SQLException {
        return SchemaFixture.insertCandidate(connection, jobId, UUID.randomUUID() + "@example.com");
    }

    @Test
    void laterEventMustDeclareWhereItCameFrom() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            UUID candidateId = freshCandidate(owner);
            SchemaFixture.insertEvent(owner, candidateId, 1, null, "APPLIED", "APPLIED", null);

            SQLException thrown = assertThrows(
                    SQLException.class,
                    () -> SchemaFixture.insertEvent(owner, candidateId, 2, null, "SCREENING", "ADVANCED", null));
            assertThat(thrown.getSQLState()).isEqualTo("23514");
            assertThat(thrown.getMessage()).contains("stage_event_first_has_no_from");
        }
    }

    @Test
    void firstEventMustNotDeclareWhereItCameFrom() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            UUID candidateId = freshCandidate(owner);

            SQLException thrown = assertThrows(
                    SQLException.class,
                    () -> SchemaFixture.insertEvent(owner, candidateId, 1, "APPLIED", "SCREENING", "ADVANCED", null));
            assertThat(thrown.getSQLState()).isEqualTo("23514");
            assertThat(thrown.getMessage()).contains("stage_event_first_has_no_from");
        }
    }

    @Test
    void anEventMustActuallyMove() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            UUID candidateId = freshCandidate(owner);
            SchemaFixture.insertEvent(owner, candidateId, 1, null, "APPLIED", "APPLIED", null);

            SQLException thrown = assertThrows(
                    SQLException.class,
                    () -> SchemaFixture.insertEvent(owner, candidateId, 2, "APPLIED", "APPLIED", "ADVANCED", null));
            assertThat(thrown.getSQLState()).isEqualTo("23514");
            assertThat(thrown.getMessage()).contains("stage_event_moves_somewhere");
        }
    }

    /**
     * from_stage is set deliberately: a seq of 0 with a null from_stage also breaks
     * stage_event_first_has_no_from, and Postgres would report that one instead.
     */
    @Test
    void sequenceStartsAtOne() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            UUID candidateId = freshCandidate(owner);

            SQLException thrown = assertThrows(
                    SQLException.class,
                    () -> SchemaFixture.insertEvent(owner, candidateId, 0, "APPLIED", "SCREENING", "ADVANCED", null));
            assertThat(thrown.getSQLState()).isEqualTo("23514");
            assertThat(thrown.getMessage()).contains("stage_event_seq_positive");
        }
    }

    @Test
    void sequenceIsUniquePerCandidate() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            UUID candidateId = freshCandidate(owner);
            SchemaFixture.insertEvent(owner, candidateId, 1, null, "APPLIED", "APPLIED", null);
            SchemaFixture.insertEvent(owner, candidateId, 2, "APPLIED", "SCREENING", "ADVANCED", null);

            SQLException thrown = assertThrows(
                    SQLException.class,
                    () -> SchemaFixture.insertEvent(owner, candidateId, 2, "SCREENING", "INTERVIEW", "ADVANCED", null));
            assertThat(thrown.getSQLState()).isEqualTo("23505");
            assertThat(thrown.getMessage()).contains("stage_event_candidate_seq_uq");
        }
    }

    @Test
    void aRetriedTransitionCannotWriteASecondEvent() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            UUID candidateId = freshCandidate(owner);
            SchemaFixture.insertEvent(owner, candidateId, 1, null, "APPLIED", "APPLIED", null);
            SchemaFixture.insertEvent(owner, candidateId, 2, "APPLIED", "SCREENING", "ADVANCED", "double-click");

            SQLException thrown = assertThrows(
                    SQLException.class,
                    () -> SchemaFixture.insertEvent(
                            owner, candidateId, 3, "SCREENING", "INTERVIEW", "ADVANCED", "double-click"));
            assertThat(thrown.getSQLState()).isEqualTo("23505");
            assertThat(thrown.getMessage()).contains("stage_event_idempotency_uq");
        }
    }

    /**
     * Documents what the partial predicate is and is not doing: Postgres treats NULLs as
     * distinct, so keyless events never collide with each other regardless.
     */
    @Test
    void keylessEventsDoNotCollide() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            UUID candidateId = freshCandidate(owner);
            SchemaFixture.insertEvent(owner, candidateId, 1, null, "APPLIED", "APPLIED", null);

            assertThatCode(() -> SchemaFixture.insertEvent(
                            owner, candidateId, 2, "APPLIED", "SCREENING", "ADVANCED", null))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void emailIsUniquePerJobIgnoringCase() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            SchemaFixture.insertCandidate(owner, jobId, "Duplicate@Example.com");

            SQLException thrown = assertThrows(
                    SQLException.class, () -> SchemaFixture.insertCandidate(owner, jobId, "duplicate@example.com"));
            assertThat(thrown.getSQLState()).isEqualTo("23505");
            assertThat(thrown.getMessage()).contains("candidate_job_email_uq");
        }
    }

    @Test
    void isTerminalTracksCurrentStage() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                var statement = owner.createStatement()) {
            UUID candidateId = freshCandidate(owner);
            statement.executeUpdate(
                    "UPDATE candidate SET current_stage = 'HIRED' WHERE id = '" + candidateId + "'");

            try (var rs = statement.executeQuery(
                    "SELECT is_terminal FROM candidate WHERE id = '" + candidateId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
    }
}
