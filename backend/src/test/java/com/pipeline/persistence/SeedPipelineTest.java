package com.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.application.SeedPipeline;
import com.pipeline.db.SchemaFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The guarantees the demo depends on, asserted as SQL rather than as Java, because what
 * matters is that a query the search feature will run actually returns rows.
 *
 * <p>Stands alone rather than extending the shared base, for two reasons that both bite.
 * It needs its own database: the seeder is idempotent by checking whether the job
 * already has candidates, and every other test class puts candidates in the shared one,
 * so it would correctly decline to do anything. And it needs the real clock: the shared
 * base pins time to 2025, while these assertions compare against Postgres now(), so a
 * frozen clock would make "moved to Interview in the last three days" permanently false.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "pipeline.seed.enabled=true",
            "pipeline.seed.candidates=200",
            "pipeline.auth.api-key=seed-test-key"
        })
class SeedPipelineTest {

    private static final String SEED_DB = SchemaFixture.freshDatabase("seed_test");

    @Autowired SeedPipeline seeder;

    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SEED_DB);
        registry.add("spring.datasource.username", () -> "pipeline_app");
        registry.add("spring.datasource.password", () -> SchemaFixture.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
    }

    @Test
    void thereAreTwoHundredCandidates() throws SQLException {
        assertThat(count("SELECT count(*) FROM candidate")).isEqualTo(200);
    }

    /** The headline search query from the brief. */
    @Test
    void atLeastEightHaveBeenStuckInScreeningForOverAWeek() throws SQLException {
        assertThat(count(
                        """
                        SELECT count(*) FROM candidate
                         WHERE current_stage = 'SCREENING' AND NOT is_terminal
                           AND current_stage_since < now() - interval '7 days'
                        """))
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    void fiveMovedToInterviewWithinTheLastThreeDays() throws SQLException {
        assertThat(count(
                        """
                        SELECT count(*) FROM stage_event
                         WHERE to_stage = 'INTERVIEW' AND occurred_at >= now() - interval '3 days'
                        """))
                .isGreaterThanOrEqualTo(5);
    }

    /** Exactly what reached_mask was denormalised for. */
    @Test
    void sixReachedOfferAndWereNotHired() throws SQLException {
        assertThat(count(
                        """
                        SELECT count(*) FROM candidate
                         WHERE (reached_mask & 8) = 8 AND (reached_mask & 16) = 0
                           AND current_stage = 'REJECTED'
                        """))
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void threeWereHired() throws SQLException {
        assertThat(count("SELECT count(*) FROM candidate WHERE current_stage = 'HIRED'"))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void priyaSharmaExists() throws SQLException {
        assertThat(count("SELECT count(*) FROM candidate WHERE full_name = 'Priya Sharma'"))
                .isEqualTo(1);
    }

    /** Histories, not a pile of rows all written at once. */
    @Test
    void eventsAreGenuinelyBackdatedAndSpreadOut() throws SQLException {
        assertThat(count("SELECT count(*) FROM stage_event WHERE occurred_at < now() - interval '30 days'"))
                .isGreaterThan(20);
        assertThat(count("SELECT count(DISTINCT date_trunc('day', occurred_at)) FROM stage_event"))
                .isGreaterThan(20);
    }

    @Test
    void everyStageOfTheBoardHasSomebodyOnIt() throws SQLException {
        assertThat(count("SELECT count(DISTINCT current_stage) FROM candidate")).isEqualTo(6);
    }

    /** Every seeded event is attributed, like every other event. */
    @Test
    void seededEventsCarryAnActor() throws SQLException {
        assertThat(count("SELECT count(*) FROM stage_event WHERE actor_id IS NULL OR actor_name IS NULL"))
                .isZero();
    }

    /** Restarting the container must not double the board. */
    @Test
    void runningAgainAddsNothing() throws SQLException {
        int candidatesBefore = count("SELECT count(*) FROM candidate");
        int eventsBefore = count("SELECT count(*) FROM stage_event");

        seeder.run(null);

        assertThat(count("SELECT count(*) FROM candidate")).isEqualTo(candidatesBefore);
        assertThat(count("SELECT count(*) FROM stage_event")).isEqualTo(eventsBefore);
    }

    private static int count(String sql) throws SQLException {
        try (Connection owner = SchemaFixture.connectAsOwnerTo(SEED_DB);
                Statement statement = owner.createStatement();
                var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
