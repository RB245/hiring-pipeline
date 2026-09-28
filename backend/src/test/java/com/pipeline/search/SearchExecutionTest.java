package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.application.CandidateSearch;
import com.pipeline.application.SearchCandidates;
import com.pipeline.application.SearchCursor;
import com.pipeline.application.SearchHit;
import com.pipeline.db.SchemaFixture;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The eight acceptance queries, run for real against a seeded pipeline.
 *
 * <p>Each one is checked against the same question written out by hand in SQL. That is the
 * point of the table below: asserting a row count would pass just as happily on a query
 * that returned the wrong twelve candidates, and asserting the ids against a literal list
 * would only re-state whatever the code did the first time it ran. An independently
 * written predicate disagreeing with the Criteria one is the failure worth catching.
 *
 * <p>Its own database and a frozen clock, for the reasons {@code SeedPipelineTest} gives
 * and one more: "since Monday" has to mean a specific Monday. The clock is a Saturday, so
 * the most recent Monday is five days back and cannot be confused with today.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "pipeline.seed.enabled=true",
            "pipeline.seed.candidates=200",
            "pipeline.auth.api-key=search-test-key"
        })
@Import(SearchExecutionTest.FrozenClock.class)
class SearchExecutionTest {

    /** A Saturday. The seed backdates from midnight on this day. */
    static final Instant NOW = Instant.parse("2025-03-01T09:00:00Z");

    private static final String AT = "timestamptz '2025-03-01T09:00:00Z'";
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String DB = SchemaFixture.freshDatabase("search_test");

    @Autowired
    SearchCandidates search;

    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB);
        registry.add("spring.datasource.username", () -> "pipeline_app");
        registry.add("spring.datasource.password", () -> SchemaFixture.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
    }

    /** A distinct bean name, since overriding the production definition is switched off. */
    @TestConfiguration
    static class FrozenClock {
        @Bean
        @Primary
        Clock frozenClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    /** What she types, and the same question written out independently. */
    record Acceptance(String query, String equivalent) {
        @Override
        public String toString() {
            return query;
        }
    }

    static List<Acceptance> acceptance() {
        return List.of(
                new Acceptance("Find Priya Sharma", "candidate_name_matches(full_name, 'priya sharma')"),
                new Acceptance(
                        "sharam", "candidate_name_matches(full_name, 'sharam') OR email = citext('sharam')"),
                new Acceptance("Who's in Interview right now?", "current_stage = 'INTERVIEW'"),
                new Acceptance(
                        "Who has been stuck in Screening for more than a week?",
                        "current_stage = 'SCREENING' AND NOT is_terminal"
                                + " AND current_stage_since < " + AT + " - interval '7 days'"),
                new Acceptance(
                        "Who moved to Interview since Monday?",
                        "EXISTS (SELECT 1 FROM stage_event e WHERE e.candidate_id = c.id"
                                + " AND e.to_stage = 'INTERVIEW'"
                                + " AND e.occurred_at >= timestamptz '2025-02-24T00:00:00Z')"),
                new Acceptance(
                        "Who reached the Offer stage but didn't get hired?",
                        "(reached_mask & 8) = 8 AND NOT (current_stage = 'HIRED')"),
                new Acceptance("Everyone except rejected candidates", "NOT (current_stage = 'REJECTED')"),
                new Acceptance(
                        "stage:interview in_stage_for:>3d -status:rejected",
                        "current_stage = 'INTERVIEW' AND NOT is_terminal"
                                + " AND current_stage_since < " + AT + " - interval '3 days'"
                                + " AND NOT (current_stage = 'REJECTED')"));
    }

    @ParameterizedTest
    @MethodSource("acceptance")
    void returnsExactlyWhatTheQuestionAsksFor(Acceptance row) throws SQLException {
        assertThat(idsFor(row.query()))
                .as("%s  ->  %s", row.query(), row.equivalent())
                .containsExactlyInAnyOrderElementsOf(idsMatching(row.equivalent()));
    }

    /** None of the eight may quietly match nobody; a passing set comparison of two empties is no test. */
    @ParameterizedTest
    @MethodSource("acceptance")
    void andFindsSomebody(Acceptance row) {
        assertThat(idsFor(row.query())).as("%s found nobody", row.query()).isNotEmpty();
    }

    /** The demo, and the reason the threshold is 0.5 rather than pg_trgm's 0.6. */
    @Test
    void sharamFindsPriyaSharma() {
        assertThat(namesFor("sharam")).contains("Priya Sharma");
    }

    /** Her name typed correctly puts her first, because an exact match scores 1.0. */
    @Test
    void findPriyaSharmaRanksHerFirst() {
        assertThat(namesFor("Find Priya Sharma")).first().isEqualTo("Priya Sharma");
    }

    /**
     * Pasting the address in identifies her outright. The name term scores a flat 1.00 —
     * the overall score is lower because recency and stage priority are weighed in too,
     * and she applied a long time ago.
     */
    @Test
    void anEmailAddressFindsItsOwnerAndScoresItAnExactIdentityMatch() {
        String email = emailOf("Priya Sharma");

        List<SearchHit> hits = hits(email);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).candidate().fullName()).isEqualTo("Priya Sharma");
        assertThat(hits.get(0).matchedOn()).containsExactly("name ~ '" + email + "' (1.00)");
    }

    /** Case is not a way to fail to find somebody: the column is citext and must stay citext. */
    @Test
    void anEmailAddressInTheWrongCaseStillFindsHer() {
        assertThat(namesFor(emailOf("Priya Sharma").toUpperCase(java.util.Locale.ROOT)))
                .contains("Priya Sharma");
    }

    /**
     * Ranking is a property of the row and the query, not of the order rows came back in,
     * so running the same search twice has to produce the same order. Without the
     * (created_at, id) tie-break underneath the score this fails intermittently, because
     * six people called Sharma score identically.
     */
    @Test
    void rankingIsStableAcrossRuns() {
        List<UUID> first = idsFor("sharam");
        for (int run = 0; run < 5; run++) {
            assertThat(idsFor("sharam")).as("run %d", run).isEqualTo(first);
        }
    }

    @Test
    void scoresDescendAndNeverLeaveTheUnitRange() {
        List<SearchHit> hits = hits("stage:screening in_stage_for:>7d");

        assertThat(hits).isNotEmpty();
        assertThat(hits).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
        assertThat(hits).allSatisfy(hit -> assertThat(hit.score()).isBetween(0.0, 1.0));
    }

    /**
     * The renormalisation. With no text in the query nameMatch is zero for everyone, so on
     * the weights as written the best possible answer would score 0.43 and read as a weak
     * match. Rescaling over the terms the query exercises is what makes the number mean
     * "a good answer to the question you asked".
     */
    @Test
    void aFilterOnlyQueryStillScoresAcrossTheWholeRange() {
        assertThat(hits("stage:screening in_stage_for:>7d")).first().satisfies(best ->
                assertThat(best.score()).isGreaterThan(0.5));
    }

    @Test
    void everyResultExplainsWhyItMatched() {
        List<SearchHit> hits = hits("stage:screening in_stage_for:>7d");

        assertThat(hits).allSatisfy(hit -> assertThat(hit.matchedOn())
                .containsExactlyInAnyOrder("stage = Screening", "in_stage_for >7d"));
    }

    /** The name score is quoted in the explanation, because it is the part she cannot check herself. */
    @Test
    void aFuzzyMatchSaysHowWellItMatched() {
        SearchHit priya = hits("sharam").stream()
                .filter(hit -> hit.candidate().fullName().equals("Priya Sharma"))
                .findFirst()
                .orElseThrow();

        assertThat(priya.matchedOn()).containsExactly("name ~ 'sharam' (0.80)");
    }

    /**
     * Under an OR the branches a row did not match are exactly what separates a good hit
     * from a merely adequate one, so the explanation lists only what was actually met.
     */
    @Test
    void anOrExplainsOnlyTheBranchThatMatched() {
        List<SearchHit> hits = hits("stage:hired OR stage:offer");

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(hit -> assertThat(hit.matchedOn()).hasSize(1));
        assertThat(hits.stream().flatMap(hit -> hit.matchedOn().stream()).distinct())
                .containsExactlyInAnyOrder("stage = Hired", "stage = Offer");
    }

    @Test
    void pagingARankedSearchRepeatsNothingAndSkipsNothing() {
        List<UUID> everything = idsFor("status:active");

        List<UUID> paged = new ArrayList<>();
        SearchCursor cursor = null;
        do {
            SearchCandidates.Results page = search.search(JOB, "status:active", cursor, 7);
            page.hits().forEach(hit -> paged.add(hit.candidate().id()));
            cursor = page.next();
        } while (cursor != null);

        assertThat(paged).isEqualTo(everything);
        assertThat(paged).doesNotHaveDuplicates();
    }

    /**
     * The invariant the recency term is built on. current_stage_since is written from the
     * occurred_at of the event that caused the move, so it is max(occurred_at) by
     * construction — which is what lets the score read one column instead of running a
     * correlated subquery per row. Pinned here because nothing else would notice it
     * drifting.
     */
    @Test
    void currentStageSinceIsTheTimeOfTheLatestEvent() throws SQLException {
        assertThat(count(
                        """
                        SELECT count(*) FROM candidate c
                         WHERE c.current_stage_since
                               <> (SELECT max(e.occurred_at) FROM stage_event e WHERE e.candidate_id = c.id)
                        """))
                .isZero();
    }

    // ---- zero results -------------------------------------------------------

    /**
     * The part that is usually skipped. A query that parsed, ran, and matched nobody is
     * indistinguishable from one she got wrong, and "0 results" leaves her deleting
     * conditions one at a time to find out which.
     */
    @Test
    void aQueryThatFindsNobodyComesBackWithSomethingToLoosen() {
        SearchCandidates.Results results =
                search.search(JOB, "stage:screening in_stage_for:>7d status:hired", null, 20);

        assertThat(results.hits()).isEmpty();
        assertThat(results.suggestions()).isNotEmpty();
        assertThat(results.suggestions())
                .allSatisfy(suggestion -> assertThat(suggestion.results()).isPositive());
        assertThat(results.suggestions().stream().map(CandidateSearch.Relaxation::without))
                .contains("status:hired");
    }

    /** Most generous first, so the first line is the condition most likely to be in the way. */
    @Test
    void suggestionsArrivedMostGenerousFirstAndCapped() {
        SearchCandidates.Results results = search.search(
                JOB, "stage:screening in_stage_for:>7d status:hired reached:offer applied:<1d", null, 20);

        assertThat(results.suggestions()).hasSizeLessThanOrEqualTo(SearchCandidates.MAX_SUGGESTIONS);
        assertThat(results.suggestions())
                .isSortedAccordingTo((a, b) -> Long.compare(b.results(), a.results()));
    }

    /** Each count is the real thing: run what it suggests and you get what it promised. */
    @Test
    void aSuggestedCountIsWhatThatQueryActuallyReturns() {
        String query = "stage:screening in_stage_for:>7d status:hired";
        SearchCandidates.Results results = search.search(JOB, query, null, 20);

        CandidateSearch.Relaxation suggestion = results.suggestions().get(0);
        String loosened = withoutCondition(query, suggestion.without());

        assertThat(idsFor(loosened)).hasSize((int) suggestion.results());
    }

    /**
     * Nothing to suggest is not the same as nothing to say. A single condition matching
     * nobody has no half of itself to drop, and "try dropping your only filter" is not
     * advice.
     */
    @Test
    void aSingleConditionThatMatchesNobodyOffersNoSuggestions() {
        SearchCandidates.Results results = search.search(JOB, "name:zzzzzznobody", null, 20);

        assertThat(results.hits()).isEmpty();
        assertThat(results.suggestions()).isEmpty();
    }

    /**
     * Suggestions are for a query that found nothing, not for the end of a long result
     * set. Reached with a cursor positioned below every row rather than by paging to the
     * end, because paging cannot get there: a next cursor is only issued when another row
     * was actually seen, so a legitimately-reached later page is never empty.
     */
    @Test
    void aLaterPageComingBackEmptyIsNotTreatedAsAFailedQuery() {
        SearchCursor pastTheEnd = new SearchCursor(0.0, Instant.EPOCH, new UUID(0, 0));

        SearchCandidates.Results past = search.search(JOB, "status:active", pastTheEnd, 20);

        assertThat(past.hits()).isEmpty();
        assertThat(past.suggestions()).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------

    private List<SearchHit> hits(String query) {
        return search.search(JOB, query, null, 500).hits();
    }

    private List<UUID> idsFor(String query) {
        return hits(query).stream().map(hit -> hit.candidate().id()).toList();
    }

    private List<String> namesFor(String query) {
        return hits(query).stream().map(hit -> hit.candidate().fullName()).toList();
    }

    private static String withoutCondition(String query, String condition) {
        return query.replace(condition, "").replaceAll("\\s+", " ").strip();
    }

    private String emailOf(String fullName) {
        return hits("name:\"" + fullName + "\"").stream()
                .filter(hit -> hit.candidate().fullName().equals(fullName))
                .findFirst()
                .orElseThrow()
                .candidate()
                .email();
    }

    private static List<UUID> idsMatching(String where) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection owner = SchemaFixture.connectAsOwnerTo(DB);
                Statement statement = owner.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT c.id FROM candidate c WHERE c.job_id = '" + JOB + "' AND (" + where + ")")) {
            while (rows.next()) {
                ids.add(rows.getObject(1, UUID.class));
            }
        }
        return ids;
    }

    private static int count(String sql) throws SQLException {
        try (Connection owner = SchemaFixture.connectAsOwnerTo(DB);
                Statement statement = owner.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
