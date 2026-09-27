package com.pipeline.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The three layers are asserted separately and by distinct SQLSTATEs, so that neither
 * can pass by being masked by another.
 */
class StageEventImmutabilityTest {

    private static UUID candidateId;

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            UUID jobId = SchemaFixture.insertJob(owner);
            candidateId = SchemaFixture.insertCandidate(owner, jobId, "priya@example.com");
            SchemaFixture.insertEvent(owner, candidateId, 1, null, "APPLIED", "APPLIED", null);
        }
    }

    // Layer 1: the trigger. Asserted as the owner, who has every privilege and is
    // therefore stopped by nothing else.

    @Test
    void ownerCannotUpdate() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                Statement statement = owner.createStatement()) {
            SQLException thrown = assertThrows(
                    SQLException.class, () -> statement.executeUpdate("UPDATE stage_event SET reason = 'edited'"));
            assertThat(thrown.getSQLState()).isEqualTo("P0001");
            assertThat(thrown.getMessage()).contains("append-only", "UPDATE");
        }
    }

    @Test
    void ownerCannotDelete() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                Statement statement = owner.createStatement()) {
            SQLException thrown =
                    assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM stage_event"));
            assertThat(thrown.getSQLState()).isEqualTo("P0001");
            assertThat(thrown.getMessage()).contains("append-only", "DELETE");
        }
    }

    @Test
    void ownerCannotTruncate() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                Statement statement = owner.createStatement()) {
            SQLException thrown =
                    assertThrows(SQLException.class, () -> statement.executeUpdate("TRUNCATE stage_event"));
            assertThat(thrown.getSQLState()).isEqualTo("P0001");
            assertThat(thrown.getMessage()).contains("append-only", "TRUNCATE");
        }
    }

    // Layer 2: the privilege split. 42501 rather than P0001 proves the grant stopped it
    // before the trigger ever ran.

    @Test
    void applicationCannotUpdate() throws SQLException {
        try (Connection app = SchemaFixture.asApplication();
                Statement statement = app.createStatement()) {
            SQLException thrown = assertThrows(
                    SQLException.class, () -> statement.executeUpdate("UPDATE stage_event SET reason = 'edited'"));
            assertThat(thrown.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    void applicationCannotDelete() throws SQLException {
        try (Connection app = SchemaFixture.asApplication();
                Statement statement = app.createStatement()) {
            SQLException thrown =
                    assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM stage_event"));
            assertThat(thrown.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    void applicationCannotDropTheTrigger() throws SQLException {
        try (Connection app = SchemaFixture.asApplication();
                Statement statement = app.createStatement()) {
            SQLException thrown = assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("DROP TRIGGER stage_event_no_update_or_delete ON stage_event"));
            assertThat(thrown.getSQLState()).isEqualTo("42501");
        }
    }

    /** The revokes must not have been drawn so wide that the app cannot record history. */
    @Test
    void applicationCanStillAppend() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner()) {
            UUID jobId = SchemaFixture.insertJob(owner);
            UUID appended = SchemaFixture.insertCandidate(owner, jobId, "appendable@example.com");
            try (Connection app = SchemaFixture.asApplication()) {
                SchemaFixture.insertEvent(app, appended, 1, null, "APPLIED", "APPLIED", null);
                SchemaFixture.insertEvent(app, appended, 2, "APPLIED", "SCREENING", "ADVANCED", "key-1");
            }
        }
    }

    // Layer 3: no cascade reaches the log. Deleting a candidate must fail rather than
    // quietly taking their history with it.

    @Test
    void deletingACandidateWithHistoryIsRefused() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                Statement statement = owner.createStatement()) {
            SQLException thrown = assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM candidate WHERE id = '" + candidateId + "'"));
            assertThat(thrown.getSQLState()).isEqualTo("23503");
        }
    }
}
