package com.pipeline.api;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class CandidateApiTest extends ApiTest {

    @Test
    void registeringACandidateReturns201WithALocationAndTheirFirstStage() throws Exception {
        mvc.perform(post("/api/v1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"fullName": "Priya Sharma", "email": "priya-%s@example.com",
                                 "phone": "+91 99999 00000", "source": "referral"}
                                """
                                        .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/candidates/")))
                .andExpect(jsonPath("$.fullName").value("Priya Sharma"))
                .andExpect(jsonPath("$.currentStage").value("APPLIED"))
                .andExpect(jsonPath("$.timeInCurrentStage").value("PT0S"))
                .andExpect(jsonPath("$.timeInCurrentStageHumanised").value("just now"));
    }

    @Test
    void timeInStageIsRenderedBothWaysFromTheInjectedClock() throws Exception {
        UUID id = createCandidate("Waiting Long");
        clock.advance(Duration.ofDays(6));

        mvc.perform(get("/api/v1/candidates/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeInCurrentStage").value("PT144H"))
                .andExpect(jsonPath("$.timeInCurrentStageHumanised").value("6 days"));
    }

    /**
     * The board renders a button per legal target, so this list is the only thing standing
     * between it and a copy of the state machine in TypeScript. Asserted here rather than
     * trusted, because the failure mode is a button that offers a move the API refuses.
     */
    @Test
    void everyCandidateCarriesTheMovesItActuallyHas() throws Exception {
        UUID id = createCandidate("Knows Its Moves");

        mvc.perform(get("/api/v1/candidates/{id}", id))
                .andExpect(jsonPath("$.currentStage").value("APPLIED"))
                .andExpect(jsonPath("$.legalTargets").value(hasItem("SCREENING")))
                .andExpect(jsonPath("$.legalTargets").value(hasItem("REJECTED")))
                .andExpect(jsonPath("$.legalTargets.length()").value(2));
    }

    /** Empty rather than absent, so "terminal" is something the board can see and explain. */
    @Test
    void aTerminalCandidateCarriesNoMovesAtAll() throws Exception {
        UUID id = createCandidate("Out Of Moves");
        mvc.perform(post("/api/v1/candidates/{id}/transitions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedCurrentStage": "APPLIED", "toStage": "REJECTED"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/candidates/{id}", id))
                .andExpect(jsonPath("$.currentStage").value("REJECTED"))
                .andExpect(jsonPath("$.legalTargets.length()").value(0));
    }

    /** And the board carries them too, since that is where the buttons are. */
    @Test
    void theBoardCarriesThemOnEveryCard() throws Exception {
        createCandidate("On The Board With Moves");

        mvc.perform(get("/api/v1/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].candidates[0].legalTargets").exists());
    }

    /**
     * A duplicate email and a lost optimistic lock arrive as the same exception and must
     * not come out as the same message. This one has to be readable enough to put next to
     * the email field in a form, which is why it names the field.
     */
    @Test
    void anEmailAlreadyOnTheBoardSaysSoRatherThanBlamingConcurrency() throws Exception {
        String email = "duplicate-%s@example.com".formatted(UUID.randomUUID());
        String body = """
                {"fullName": "First Arrival", "email": "%s", "source": "referral"}
                """.formatted(email);
        mvc.perform(post("/api/v1/candidates").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/candidates").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("duplicate-email")))
                .andExpect(jsonPath("$.field").value("email"))
                .andExpect(jsonPath("$.detail").value("Somebody with that email address is already in this pipeline."));
    }

    @Test
    void oneDayIsSingular() throws Exception {
        UUID id = createCandidate("One Day");
        clock.advance(Duration.ofDays(1).plusHours(3));

        mvc.perform(get("/api/v1/candidates/{id}", id))
                .andExpect(jsonPath("$.timeInCurrentStageHumanised").value("1 day"));
    }

    @Test
    void theTimelineIsReturnedAscendingBySeq() throws Exception {
        UUID id = createCandidate("Moving Along");
        advance(id, "APPLIED", "SCREENING");
        advance(id, "SCREENING", "INTERVIEW");

        mvc.perform(get("/api/v1/candidates/{id}/events", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].toStage").value("APPLIED"))
                .andExpect(jsonPath("$[0].fromStage").doesNotExist())
                .andExpect(jsonPath("$[1].toStage").value("SCREENING"))
                .andExpect(jsonPath("$[1].eventType").value("ADVANCED"))
                .andExpect(jsonPath("$[2].toStage").value("INTERVIEW"));
    }

    @Test
    void theTimelineOfAnUnknownCandidateIs404() throws Exception {
        mvc.perform(get("/api/v1/candidates/{id}/events", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theListPagesByCursorAndTheCursorIsOpaque() throws Exception {
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMinutes(1));
            createCandidate("Pager " + i);
        }

        MvcResult first = mvc.perform(get("/api/v1/candidates").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();

        JsonNode body = json.readTree(first.getResponse().getContentAsString());
        String cursor = body.get("nextCursor").asText();
        String firstId = body.get("candidates").get(0).get("id").asText();

        mvc.perform(get("/api/v1/candidates").param("cursor", cursor).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[*].id").value(org.hamcrest.Matchers.not(hasItem(firstId))));
    }

    @Test
    void theBoardGroupsByStageAndKeepsEveryColumn() throws Exception {
        UUID screened = createCandidate("On The Board");
        advance(screened, "APPLIED", "SCREENING");

        mvc.perform(get("/api/v1/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(6))
                .andExpect(jsonPath("$.columns[0].stage").value("APPLIED"))
                .andExpect(jsonPath("$.columns[5].stage").value("REJECTED"))
                .andExpect(jsonPath("$.columns[1].count").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.columns[1].candidates[*].id").value(hasItem(screened.toString())));
    }

    /** The count on a column must be the size of the list it sits above, always. */
    @Test
    void everyColumnCountMatchesItsOwnList() throws Exception {
        createCandidate("Counted");

        MvcResult result = mvc.perform(get("/api/v1/pipeline")).andReturn();
        JsonNode columns = json.readTree(result.getResponse().getContentAsString()).get("columns");

        for (JsonNode column : columns) {
            org.assertj.core.api.Assertions.assertThat(column.get("count").asInt())
                    .isEqualTo(column.get("candidates").size());
        }
    }

    @Test
    void theAdminSweepReportsHowManyProjectionsWereWrong() throws Exception {
        createCandidate("Swept");

        mvc.perform(post("/api/v1/admin/rebuild-projections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebuilt").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.changed").value(0));
    }

    private void advance(UUID id, String from, String to) throws Exception {
        mvc.perform(post("/api/v1/candidates/{id}/transitions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedCurrentStage": "%s", "toStage": "%s"}
                                """.formatted(from, to)))
                .andExpect(status().isCreated());
    }
}
