package com.pipeline.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One real Postgres for the whole test run, migrated once from empty. Real Postgres
 * rather than H2 because enums, partial indexes, generated columns, triggers and role
 * grants are the things under test and H2 would wave most of them through.
 */
public final class SchemaFixture {

    public static final String APP_PASSWORD = "app-password";
    static final OffsetDateTime T0 = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .placeholders(Map.of("app_password", APP_PASSWORD))
                .load()
                .migrate();
    }

    /** Shared with the persistence tests so the whole run needs only one container. */
    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /**
     * How many connections one test context may hold.
     *
     * <p>Small on purpose, and the reason is not frugality. Spring caches a context per
     * distinct configuration and never closes it, so every context's pool is held open for
     * the whole run — at Hikari's default of ten, a suite with a dozen configurations
     * exhausts Postgres's hundred connection slots and the next context to start fails
     * with "remaining connection slots are reserved", which surfaces as an unrelated
     * "unable to determine Dialect". Five is comfortably above the two threads the most
     * concurrent test uses.
     */
    public static final String MAX_POOL_SIZE = "5";

    /**
     * A second, empty, fully migrated database on the same container.
     *
     * <p>Needed because rows cannot be cleaned up between test classes: stage_event
     * refuses DELETE and candidate is pinned by its foreign key. A test whose subject is
     * "what happens against an empty pipeline" therefore cannot share a database with
     * tests that fill one, and a whole extra container to get that would be waste.
     */
    public static String freshDatabase(String name) {
        try (Connection owner = asOwner();
                java.sql.Statement statement = owner.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name);
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create database " + name, e);
        }

        String url = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), name);
        Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .placeholders(Map.of("app_password", APP_PASSWORD))
                .load()
                .migrate();
        return url;
    }

    public static Connection connectAsOwnerTo(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The identity Flyway ran as: a member of pipeline_migrator, so it holds DDL. */
    public static Connection asOwner() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The identity Spring's datasource uses. */
    static Connection asApplication() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "pipeline_app", APP_PASSWORD);
    }

    static UUID insertJob(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO job (id, title, created_at) VALUES (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, "Backend Engineer");
            statement.setObject(3, T0);
            statement.executeUpdate();
        }
        return id;
    }

    static UUID insertCandidate(Connection connection, UUID jobId, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO candidate (id, job_id, full_name, email, current_stage,
                                       current_stage_since, reached_mask, created_at, version)
                VALUES (?, ?, ?, ?, CAST(? AS stage), ?, ?, ?, 0)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, jobId);
            statement.setString(3, "Priya Sharma");
            statement.setString(4, email);
            statement.setString(5, "APPLIED");
            statement.setObject(6, T0);
            statement.setShort(7, (short) 1);
            statement.setObject(8, T0);
            statement.executeUpdate();
        }
        return id;
    }

    /** Nullable from_stage and idempotency_key, since both are what the constraints police. */
    static void insertEvent(
            Connection connection,
            UUID candidateId,
            int seq,
            String fromStage,
            String toStage,
            String eventType,
            String idempotencyKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stage_event (candidate_id, seq, from_stage, to_stage, event_type,
                                         occurred_at, actor_id, actor_name, idempotency_key)
                VALUES (?, ?, CAST(? AS stage), CAST(? AS stage), CAST(? AS event_type), ?, ?, ?, ?)
                """)) {
            statement.setObject(1, candidateId);
            statement.setInt(2, seq);
            statement.setObject(3, fromStage, Types.VARCHAR);
            statement.setString(4, toStage);
            statement.setString(5, eventType);
            statement.setObject(6, T0);
            statement.setString(7, "recruiter-1");
            statement.setString(8, "Asha");
            statement.setObject(9, idempotencyKey, Types.VARCHAR);
            statement.executeUpdate();
        }
    }

    private SchemaFixture() {}
}
