package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class TransitionApiTest extends ApiTest {

    @Test
    void aLegalMoveRecordsOneEvent() throws Exception {
        UUID id = createCandidate("Advancing");
        clock.advance(Duration.ofDays(1));

        mvc.perform(transition(id, "APPLIED", "SCREENING", "Strong screen", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.candidateId").value(id.toString()))
                .andExpect(jsonPath("$.fromStage").value("APPLIED"))
                .andExpect(jsonPath("$.toStage").value("SCREENING"))
                .andExpect(jsonPath("$.eventType").value("ADVANCED"))
                .andExpect(jsonPath("$.reason").value("Strong screen"));

        assertThat(countEvents(id)).isEqualTo(2);
    }

    @Test
    void anIllegalMoveIs422AndSaysWhatWouldHaveWorked() throws Exception {
        UUID id = createCandidate("Skipping Ahead");

        mvc.perform(transition(id, "APPLIED", "OFFER", null, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://pipeline.example/problems/illegal-transition"))
                .andExpect(jsonPath("$.fromStage").value("APPLIED"))
                .andExpect(jsonPath("$.toStage").value("OFFER"))
                .andExpect(jsonPath("$.legalTargets").isArray())
                .andExpect(jsonPath("$.legalTargets[0]").value("SCREENING"))
                .andExpect(jsonPath("$.legalTargets[1]").value("REJECTED"));

        assertThat(countEvents(id)).isEqualTo(1);
    }

    @Test
    void aTerminalCandidateOffersNoAlternatives() throws Exception {
        UUID id = createCandidate("Already Rejected");
        mvc.perform(transition(id, "APPLIED", "REJECTED", null, null)).andExpect(status().isCreated());

        mvc.perform(transition(id, "REJECTED", "SCREENING", null, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.legalTargets").isEmpty());
    }

    @Test
    void aStaleExpectedStageIs409AndWritesNothing() throws Exception {
        UUID id = createCandidate("Stale Board");
        mvc.perform(transition(id, "APPLIED", "SCREENING", null, null)).andExpect(status().isCreated());

        // The recruiter's board still shows APPLIED; someone else already moved them.
        mvc.perform(transition(id, "APPLIED", "SCREENING", null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://pipeline.example/problems/stale-candidate-state"))
                .andExpect(jsonPath("$.expectedCurrentStage").value("APPLIED"))
                .andExpect(jsonPath("$.actualCurrentStage").value("SCREENING"));

        assertThat(countEvents(id)).isEqualTo(2);
    }

    /**
     * The double-click. Same key twice must produce one event and two identical
     * responses, so the caller cannot tell which of them did the work.
     *
     * <p>The clock is deliberately set to a sub-microsecond instant. The first response
     * is built from the event in memory and the replay is read back from Postgres,
     * which keeps microseconds and rounds; without truncation at the point the event is
     * stamped, the two bodies differ in the last few digits of occurredAt and nothing
     * coarser would ever notice.
     */
    @Test
    void replayingAnIdempotencyKeyReturnsTheOriginalAndWritesNothingNew() throws Exception {
        clock.set(Instant.parse("2025-03-01T09:00:00.347750677Z"));
        UUID id = createCandidate("Double Clicked");
        String key = UUID.randomUUID().toString();

        MvcResult first = mvc.perform(transition(id, "APPLIED", "SCREENING", "Strong screen", key))
                .andExpect(status().isCreated())
                .andReturn();

        clock.advance(Duration.ofDays(3));

        MvcResult replay = mvc.perform(transition(id, "APPLIED", "SCREENING", "Strong screen", key))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(countEvents(id)).isEqualTo(2);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    /**
     * The replay is answered before expectedCurrentStage is looked at, which is what
     * makes a retry safe: the candidate has already moved because of the first call.
     */
    @Test
    void aReplayIsNotDefeatedByTheStageItAlreadyCaused() throws Exception {
        UUID id = createCandidate("Retry After Success");
        String key = UUID.randomUUID().toString();

        mvc.perform(transition(id, "APPLIED", "SCREENING", null, key)).andExpect(status().isCreated());
        mvc.perform(transition(id, "APPLIED", "SCREENING", null, key)).andExpect(status().isCreated());

        assertThat(countEvents(id)).isEqualTo(2);
    }

    @Test
    void differentKeysOnTheSameCandidateAreDifferentTransitions() throws Exception {
        UUID id = createCandidate("Two Moves");

        mvc.perform(transition(id, "APPLIED", "SCREENING", null, UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
        mvc.perform(transition(id, "SCREENING", "INTERVIEW", null, UUID.randomUUID().toString()))
                .andExpect(status().isCreated());

        assertThat(countEvents(id)).isEqualTo(3);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder transition(
            UUID id, String from, String to, String reason, String idempotencyKey) {
        var builder = post("/api/v1/candidates/{id}/transitions", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {"expectedCurrentStage": "%s", "toStage": "%s"%s}
                        """
                                .formatted(from, to, reason == null ? "" : ", \"reason\": \"" + reason + "\""));
        return idempotencyKey == null ? builder : builder.header("Idempotency-Key", idempotencyKey);
    }
}
