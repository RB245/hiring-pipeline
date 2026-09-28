package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The HTTP surface of search: the two query endpoints, the {@code q=} on the list, and the
 * bucket the whole lot draws on.
 *
 * <p>Creates its own two candidates rather than leaning on the seed, because this class
 * runs against the database every other API test also writes to. What is under test here
 * is the wiring, not the ranking — {@code SearchExecutionTest} owns that against a
 * pipeline whose shape is known.
 */
@TestPropertySource(
        properties = {
            // Still effectively unlimited, but distinguishable. The shared base sets all
            // three tiers to the same number, which would make "search is on its own
            // bucket" pass without the routing existing at all.
            "pipeline.rate-limit.write=1000000",
            "pipeline.rate-limit.read=1000000",
            "pipeline.rate-limit.search=999999"
        })
class SearchApiTest extends ApiTest {

    private static final String SEARCH_LIMIT = "999999";
    private static final String READ_LIMIT = "1000000";

    private static boolean created;

    @BeforeEach
    void twoPeopleToFind() throws Exception {
        if (!created) {
            createCandidate("Priya Sharma");
            createCandidate("Rahul Verma");
            created = true;
        }
    }

    // ---- /explain -----------------------------------------------------------

    @Test
    void explainShowsTheCanonicalFormOfASentence() throws Exception {
        JsonNode body = getJson("/api/v1/search/explain", "q", "Who has been stuck in Screening for more than a week?");

        assertThat(body.get("dsl").asText()).isEqualTo("stage:screening in_stage_for:>7d");
        assertThat(body.get("query").asText()).startsWith("Who has been stuck");
    }

    /** All eight, because /explain is how a reviewer is shown that the parse is real. */
    @Test
    void explainHandlesEveryAcceptanceQuery() throws Exception {
        String[][] expected = {
            {"Find Priya Sharma", "name:\"priya sharma\""},
            {"sharam", "sharam"},
            {"Who's in Interview right now?", "stage:interview"},
            {"Who has been stuck in Screening for more than a week?", "stage:screening in_stage_for:>7d"},
            {"Who moved to Interview since Monday?", "moved_to:interview since:monday"},
            {"Who reached the Offer stage but didn't get hired?", "reached:offer -status:hired"},
            {"Everyone except rejected candidates", "-status:rejected"},
            {"stage:interview in_stage_for:>3d -status:rejected", "stage:interview in_stage_for:>3d -status:rejected"}
        };
        for (String[] row : expected) {
            assertThat(getJson("/api/v1/search/explain", "q", row[0]).get("dsl").asText())
                    .as("%s", row[0])
                    .isEqualTo(row[1]);
        }
    }

    /**
     * The reason the endpoint exists rather than just a pretty-printer: a recruiter cannot
     * check "since:monday" but she can check a date.
     */
    @Test
    void explainResolvesRelativeDatesToTheInstantTheyMean() throws Exception {
        JsonNode ast = getJson("/api/v1/search/explain", "q", "Who moved to Interview since Monday?").get("ast");

        assertThat(ast.get("type").asText()).isEqualTo("predicate");
        assertThat(ast.get("field").asText()).isEqualTo("moved_to");
        assertThat(ast.get("means").asText()).contains("an event into Interview", "at or after 2025-02-24T00:00:00Z");
    }

    @Test
    void explainResolvesDurationsToAThresholdRatherThanADuration() throws Exception {
        JsonNode ast = getJson("/api/v1/search/explain", "q", "in_stage_for:>7d").get("ast");

        assertThat(ast.get("means").asText()).isEqualTo("the timestamp is before 2025-02-22T09:00:00Z");
    }

    @Test
    void explainCarriesTheSpansTheBoxNeedsToUnderline() throws Exception {
        JsonNode ast = getJson("/api/v1/search/explain", "q", "stage:interview -status:rejected").get("ast");

        JsonNode negation = ast.get("children").get(1);
        assertThat(negation.get("type").asText()).isEqualTo("not");
        // "-status:rejected" begins at 16 and runs to the end of the 32-character query.
        assertThat(negation.get("source").toString()).isEqualTo("[16,32]");
    }

    /** The same failure, the same code and the same span as running it would give. */
    @Test
    void explainRejectsABadQueryRatherThanGuessing() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/search/explain").param("q", "stage:Intervew")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("UNKNOWN_STAGE");
        assertThat(body.get("span").toString()).isEqualTo("[6,14]");
        assertThat(body.get("didYouMean").get(0).asText()).isEqualTo("Interview");
    }

    // ---- /suggest -----------------------------------------------------------

    @Test
    void suggestCompletesAFieldName() throws Exception {
        JsonNode body = getJson("/api/v1/search/suggest", "q", "sta");

        assertThat(body.get("replacing").toString()).isEqualTo("[0,3]");
        assertThat(values(body)).containsExactly("stage:", "status:");
        assertThat(body.get("completions").get(0).get("kind").asText()).isEqualTo("FIELD");
    }

    @Test
    void suggestCompletesAStageValue() throws Exception {
        JsonNode body = getJson("/api/v1/search/suggest", "q", "stage:in");

        assertThat(values(body)).containsExactly("stage:interview");
        assertThat(body.get("completions").get(0).get("kind").asText()).isEqualTo("VALUE");
    }

    /** It is asked about half-written queries by definition, so it must never 422. */
    @Test
    void suggestToleratesInputTheParserWouldReject() throws Exception {
        for (String partial : new String[] {"", "stage:", "-", "name:\"pri", "(stage:offer"}) {
            assertThat(mvc.perform(get("/api/v1/search/suggest").param("q", partial))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as("q=%s", partial)
                    .isEqualTo(200);
        }
    }

    // ---- q= on the candidates list -----------------------------------------

    @Test
    void theListSearchesWhenGivenAQuery() throws Exception {
        JsonNode body = getJson("/api/v1/candidates", "q", "sharam");

        assertThat(body.get("query").asText()).isEqualTo("sharam");
        assertThat(names(body)).contains("Priya Sharma");
        assertThat(body.get("candidates").get(0).get("score").asDouble()).isBetween(0.0, 1.0);
        assertThat(body.get("candidates").get(0).get("matchedOn").get(0).asText()).startsWith("name ~ 'sharam'");
    }

    /**
     * A plain list is not a search and must not start claiming to be one. Both fields are
     * absent rather than null, so a client can tell the two apart.
     */
    @Test
    void thePlainListCarriesNoSearchFields() throws Exception {
        JsonNode body = getJson("/api/v1/candidates");

        assertThat(body.has("query")).isFalse();
        assertThat(body.has("suggestions")).isFalse();
        assertThat(body.get("candidates").get(0).has("score")).isFalse();
        assertThat(body.get("candidates").get(0).has("matchedOn")).isFalse();
    }

    @Test
    void aSearchThatFindsNobodyExplainsWhatToLoosen() throws Exception {
        JsonNode body = getJson("/api/v1/candidates", "q", "name:sharma status:hired");

        assertThat(body.get("candidates")).isEmpty();
        // Only one of the two conditions unlocks anything: nobody in this database has
        // been hired, so dropping the name instead would still find nobody and is not
        // offered. A suggestion that leads to another empty page is not a suggestion.
        assertThat(values(body.get("suggestions"), "without")).containsExactly("status:hired");
        assertThat(body.get("suggestions").get(0).get("results").asLong()).isPositive();
    }

    @Test
    void anUnparseableQueryOnTheListFailsTheSameWayItDoesOnExplain() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/candidates").param("q", "frobnicate:yes")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                        .get("code")
                        .asText())
                .isEqualTo("UNKNOWN_FIELD");
    }

    /**
     * The list and a search are ordered by different keys, so a cursor from one is not a
     * position in the other. Rejected rather than accepted, because accepting it would
     * page through the wrong ordering and look like it had worked.
     */
    @Test
    void aListCursorIsNotASearchCursor() throws Exception {
        String listCursor = getJson("/api/v1/candidates", "limit", "1").get("nextCursor").asText();

        MvcResult result = mvc.perform(
                        get("/api/v1/candidates").param("q", "status:active").param("cursor", listCursor))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                        .get("type")
                        .asText())
                .endsWith("malformed-cursor");
    }

    @Test
    void andASearchCursorIsNotAListCursor() throws Exception {
        String searchCursor = getJson("/api/v1/candidates", "q", "status:active", "limit", "1")
                .get("nextCursor")
                .asText();

        MvcResult result =
                mvc.perform(get("/api/v1/candidates").param("cursor", searchCursor)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    // ---- rate limiting ------------------------------------------------------

    /**
     * Search draws on its own bucket, and by what the request does rather than where it is
     * mapped: a q= on the candidates list costs the same trigram scan as /search/explain
     * and must not be charged to the 300/min read tier.
     */
    @Test
    void everyShapeOfSearchIsChargedToTheSearchBucket() throws Exception {
        for (String path : new String[] {"/api/v1/search/explain", "/api/v1/search/suggest", "/api/v1/candidates"}) {
            assertThat(limitOf(path, "q", "stage:offer")).as("%s", path).isEqualTo(SEARCH_LIMIT);
        }
    }

    @Test
    void whileTheBoardAndTheListStayOnTheReadBucket() throws Exception {
        assertThat(limitOf("/api/v1/candidates")).isEqualTo(READ_LIMIT);
        assertThat(limitOf("/api/v1/pipeline")).isEqualTo(READ_LIMIT);
    }

    private String limitOf(String path, String... params) throws Exception {
        return perform(path, params).getResponse().getHeader("X-RateLimit-Limit");
    }

    /**
     * Parameters are bound rather than spliced into the path. A query is full of
     * characters a URL does not take literally — {@code >}, spaces, quotes — and building
     * the string by hand quietly delivers a different query to the controller than the one
     * the test reads as being under test.
     */
    private JsonNode getJson(String path, String... params) throws Exception {
        MvcResult result = perform(path, params);
        assertThat(result.getResponse().getStatus())
                .as("%s -> %s", path, result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult perform(String path, String... params) throws Exception {
        var request = get(path);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andReturn();
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.get(field).asText()));
        return values;
    }

    private static List<String> values(JsonNode body) {
        return values(body.get("completions"), "value");
    }

    private static List<String> names(JsonNode body) {
        return values(body.get("candidates"), "fullName");
    }
}
