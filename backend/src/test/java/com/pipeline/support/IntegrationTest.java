package com.pipeline.support;

import com.pipeline.db.SchemaFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real Postgres, and connected as pipeline_app rather than the owner, so these tests
 * run under exactly the privileges production has. A missing grant fails here rather
 * than in production.
 *
 * <p>Flyway is off because {@link SchemaFixture} has already migrated the shared
 * container; touching it from the property supplier is what triggers that.
 *
 * <p>Deliberately carries no {@code @SpringBootTest} of its own, so subclasses can
 * choose a mock, none or random-port web environment without fighting an inherited one.
 */
@Import(IntegrationTest.TestClock.class)
public abstract class IntegrationTest {

    public static final Instant TEST_START = Instant.parse("2025-03-01T09:00:00Z");

    @Autowired(required = false)
    private MutableClock clockUnderTest;

    /**
     * The clock is a singleton in a cached context, so a class that leaves it advanced
     * would otherwise hand that state to whichever class runs next. Reset rather than
     * trusted.
     */
    @BeforeEach
    void resetClock() {
        if (clockUnderTest != null) {
            clockUnderTest.set(TEST_START);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SchemaFixture::jdbcUrl);
        registry.add("spring.datasource.username", () -> "pipeline_app");
        registry.add("spring.datasource.password", () -> SchemaFixture.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
    }

    @TestConfiguration
    public static class TestClock {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock(TEST_START);
        }
    }

    /** pipeline_app has SELECT only on job, so fixtures create it as the owner. */
    protected static UUID createJob() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement =
                        owner.prepareStatement("INSERT INTO job (id, title, created_at) VALUES (?, ?, now())")) {
            statement.setObject(1, id);
            statement.setString(2, "Backend Engineer");
            statement.executeUpdate();
        }
        return id;
    }

    /**
     * The one job opening the API resolves. Dated in 2000 and given a fixed id so it
     * always sorts first and is created at most once, however many test classes share
     * the container. Rows cannot be cleaned up between classes: stage_event refuses
     * DELETE and candidate is pinned by its foreign key, so the fixture has to be
     * stable rather than fresh.
     */
    protected static final UUID CANONICAL_JOB = UUID.fromString("00000000-0000-0000-0000-000000000001");

    protected static void ensureCanonicalJob() throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement = owner.prepareStatement(
                        """
                        INSERT INTO job (id, title, created_at)
                        VALUES (?, 'Backend Engineer', timestamptz '2000-01-01 00:00:00Z')
                        ON CONFLICT (id) DO NOTHING
                        """)) {
            statement.setObject(1, CANONICAL_JOB);
            statement.executeUpdate();
        }
    }

    protected static int countEvents(UUID candidateId) throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement =
                        owner.prepareStatement("SELECT count(*) FROM stage_event WHERE candidate_id = ?")) {
            statement.setObject(1, candidateId);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    protected static boolean candidateExists(UUID candidateId) throws SQLException {
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement = owner.prepareStatement("SELECT 1 FROM candidate WHERE id = ?")) {
            statement.setObject(1, candidateId);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
