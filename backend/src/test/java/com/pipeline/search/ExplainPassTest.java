package com.pipeline.search;

import com.pipeline.db.SchemaFixture;
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
 * THIS CLASS DOES NOT RUN IN A NORMAL BUILD, AND EVERY PERFORMANCE NUMBER IN
 * {@code docs/search.md} COMES FROM IT.
 *
 * <p>Saying that loudly because a skipped test is how measurements turn into folklore. A
 * green suite that reports "413 tests, 1 skipped" looks like a green suite; the one skipped
 * is the only thing standing behind every millisecond figure this project quotes. If those
 * numbers are ever cited in a review, the honest caveat is that they were measured once, by
 * hand, on the machine and the date recorded next to them — not that they are re-checked on
 * every commit. They are not.
 *
 * <p><strong>Why it is disabled.</strong> It needs 50,000 candidates and 152,000 events,
 * which take a few seconds to generate and about 25 MB, and which no test fixture builds.
 * Seeding that on every build to re-check numbers that only move when the query shapes move
 * is the wrong trade. It is enabled by an environment variable rather than deleted, so that
 * the next person to change a field handler can re-run it rather than re-invent it.
 *
 * <p>It asserts nothing. It exists to drive the real query path so the plans can be read
 * off the server log — reading the plan of a query you retyped by hand proves only that you
 * retyped it consistently.
 *
 * <p><strong>Exactly how to run it.</strong> From the repository root:
 *
 * <pre>
 * docker run -d --name pipeline-perf -p 55432:5432 \
 *     -e POSTGRES_USER=pipeline -e POSTGRES_PASSWORD=pipeline -e POSTGRES_DB=pipeline postgres:16
 *
 * # V1-V9, with V7's placeholder filled in
 * for f in backend/src/main/resources/db/migration/V*.sql; do
 *     sed 's/${app_password}/app-password/g' "$f" \
 *         | docker exec -i pipeline-perf psql -q -U pipeline -d pipeline -v ON_ERROR_STOP=1
 * done
 *
 * docker exec -i pipeline-perf psql -q -U pipeline -d pipeline \
 *     &lt; backend/src/test/resources/perf/50k-candidates.sql
 *
 * # log every statement with its bound parameters
 * docker exec pipeline-perf psql -U pipeline -d pipeline \
 *     -c "ALTER DATABASE pipeline SET log_min_duration_statement = 0" \
 *     -c "ALTER DATABASE pipeline SET log_parameter_max_length = -1"
 *
 * cd backend &amp;&amp; SPIKE_DB=jdbc:postgresql://localhost:55432/pipeline \
 *     ./gradlew test --tests ExplainPassTest
 *
 * docker logs pipeline-perf     # the SQL Hibernate emitted, and its parameters
 * </pre>
 *
 * <p>Then substitute the parameters into each statement and run it under
 * {@code EXPLAIN (ANALYZE, COSTS OFF)} with {@code max_parallel_workers_per_gather = 0},
 * which is how the table in {@code docs/search.md} was produced. Finish with
 * {@code docker rm -f pipeline-perf}.
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> SchemaFixture.MAX_POOL_SIZE);
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
        // Queries that match nobody, so the relaxation scan is driven too. "pryia" is the
        // expensive one: it reaches candidate_name_typo, which is the slowest thing here.
        for (String empty : new String[] {"stage:offer status:hired in_stage_for:>7d", "pryia", "zzzzzz"}) {
            search.search(JOB, empty, null, 20);
        }
    }
}
