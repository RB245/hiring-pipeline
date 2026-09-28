package com.pipeline.search;

import com.pipeline.application.SearchCandidates;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Not a test, and it asserts nothing. A harness for driving the real query path against a
 * database large enough for the planner to have opinions, so that the plans in
 * {@code docs/search.md} are taken from the SQL Hibernate actually emits rather than from
 * SQL written by hand to look like it.
 *
 * <p>Disabled unless SPIKE_DB names such a database, because it needs 50k candidates that
 * no test fixture builds and that no CI run should wait for.
 *
 * <p>To reproduce the numbers: bring up a Postgres, apply V1–V9, generate 50k candidates,
 * turn on {@code ALTER DATABASE ... SET log_min_duration_statement = 0}, then
 *
 * <pre>
 * SPIKE_DB=jdbc:postgresql://localhost:5432/pipeline ./gradlew test --tests ExplainPassTest
 * docker logs &lt;container&gt;      # the statements, with their bound parameters
 * </pre>
 *
 * and EXPLAIN what comes out. Reading the plan of a query you retyped proves only that you
 * retyped it consistently.
 */
@EnabledIfEnvironmentVariable(named = "SPIKE_DB", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"pipeline.seed.enabled=false", "pipeline.auth.api-key=spike"})
@Import(ExplainPassTest.FrozenClock.class)
class ExplainPassTest {

    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    SearchCandidates search;

    @DynamicPropertySource
    static void spike(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SPIKE_DB"));
        registry.add("spring.datasource.username", () -> "pipeline_app");
        registry.add("spring.datasource.password", () -> "app-password");
        registry.add("spring.flyway.enabled", () -> false);
    }

    @TestConfiguration
    static class FrozenClock {
        @Bean
        @Primary
        Clock frozenClock() {
            return Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void driveEveryShapeOfQuery() {
        for (String query : new String[] {
            "stage:interview",
            "stage:interview in_stage_for:>7d",
            "reached:offer -status:hired",
            "sharam",
            "name:sharma",
            "moved_to:interview since:monday",
            "status:active applied:<30d"
        }) {
            search.search(JOB, query, null, 20);
        }
        search.search(JOB, "stage:offer status:hired in_stage_for:>7d", null, 20);
    }
}
