package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipeline.application.CandidateSearch;
import com.pipeline.application.SearchBudgetExceededException;
import com.pipeline.application.SearchCandidates;
import com.pipeline.application.Suggestion;
import com.pipeline.db.SchemaFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The bound on the looser-match retry, proved rather than configured.
 *
 * <p>The retry cannot use an index, so its cost grows with the pipeline: 49ms over a seeded
 * 200, 3.0s over 50k. The budget is what stops that becoming a hang on a tier that allows
 * sixty searches a minute. A timeout nobody has ever seen fire is a timeout nobody knows
 * works, so this class sets an impossible one and watches what happens.
 *
 * <p>The interesting half is not that it trips. It is that tripping costs her only the one
 * suggestion: a statement cancelled by Postgres leaves its transaction unusable, so the
 * retry runs in its own, and the rest of the response has to survive it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "pipeline.seed.enabled=true",
            "pipeline.seed.candidates=200",
            "pipeline.auth.api-key=budget-test-key",
            // A budget no query can meet, so the retry always trips.
            "pipeline.search.relaxation-budget=1ms"
        })
@Import(RelaxationBudgetTest.FrozenClock.class)
class RelaxationBudgetTest {

    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String DB = SchemaFixture.freshDatabase("budget_test");

    @Autowired
    SearchCandidates search;

    @Autowired
    CandidateSearch port;

    @Autowired
    SearchQueryParser parser;

    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB);
        registry.add("spring.datasource.username", () -> "pipeline_app");
        registry.add("spring.datasource.password", () -> SchemaFixture.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> SchemaFixture.MAX_POOL_SIZE);
    }

    @TestConfiguration
    static class FrozenClock {
        @Bean
        @Primary
        Clock frozenClock() {
            return Clock.fixed(Instant.parse("2025-03-01T09:00:00Z"), ZoneOffset.UTC);
        }
    }

    /** The bound is the database's, not a hopeful sleep in Java. */
    @Test
    void anImpossibleBudgetIsRefusedByThePostgresThatIsDoingTheWork() {
        assertThatThrownBy(() -> port.countsWithin(
                        JOB, List.of(parser.parse("name_like:pryia")), Duration.ofMillis(1)))
                .isInstanceOf(SearchBudgetExceededException.class)
                .hasMessageContaining("1ms");
    }

    /**
     * What she loses is a line of text. "pryia" would normally come back with a looser-match
     * suggestion; over budget it comes back with none, rather than with an error.
     */
    @Test
    void aTrippedBudgetCostsHerTheSuggestionAndNothingElse() {
        SearchCandidates.Results results = search.search(JOB, "pryia", null, 20);

        assertThat(results.hits()).isEmpty();
        assertThat(results.suggestions()).isEmpty();
        assertThat(results.query().dsl()).isEqualTo("pryia");
    }

    /**
     * And the rest of the answer survives. A cancelled statement poisons its transaction,
     * so if the retry shared the caller's one this query would fail outright instead of
     * returning the cheap suggestion it can still afford.
     */
    @Test
    void theSuggestionsThatDoNotNeedTheBudgetStillArrive() {
        SearchCandidates.Results results = search.search(JOB, "name:pryia status:hired", null, 20);

        assertThat(results.hits()).isEmpty();
        assertThat(results.suggestions().stream().map(Suggestion::label))
                .contains("without name:pryia")
                .noneMatch(label -> label.contains("looser"));
    }

    /** And a search that finds people is untouched by any of it. */
    @Test
    void aSearchThatFindsSomebodyNeverReachesTheBudgetAtAll() {
        assertThat(search.search(JOB, "stage:screening", null, 20).hits()).isNotEmpty();
    }
}
