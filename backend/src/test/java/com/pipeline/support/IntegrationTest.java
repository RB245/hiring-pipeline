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
import org.springframework.test.context.TestPropertySource;

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
@TestPropertySource(
        properties = {
            // Seeding is off by default: 200 extra candidates would swamp every board
            // and paging assertion. SeedPipelineTest turns it back on for itself.
            "pipeline.seed.enabled=false",
            // Effectively unlimited. The limiter's buckets live in a cached application
            // context shared by every class using it, so production limits would have
            // one suite's traffic throttling the next one's. RateLimitApiTest sets its
            // own small limits, and these are declared here rather than in
            // @DynamicPropertySource because a dynamic source outranks a subclass's
            // @TestPropertySource and could not then be overridden.
            "pipeline.rate-limit.write=1000000",
            "pipeline.rate-limit.read=1000000",
            "pipeline.rate-limit.search=1000000"
        })
public abstract class IntegrationTest {

    public static final Instant TEST_START = Instant.parse("2025-03-01T09:00:00Z");

    public static final String API_KEY = "test-api-key";
    public static final String RECRUITER_ID = "recruiter-1";
    public static final String RECRUITER_NAME = "Asha Menon";

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> SchemaFixture.MAX_POOL_SIZE);
        registry.add("pipeline.auth.api-key", () -> API_KEY);
        registry.add("pipeline.auth.recruiter-id", () -> RECRUITER_ID);
        registry.add("pipeline.auth.recruiter-name", () -> RECRUITER_NAME);
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

    /** Inserted by migration V8, which is the only identity allowed to create it. */
    protected static final UUID CANONICAL_JOB = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
