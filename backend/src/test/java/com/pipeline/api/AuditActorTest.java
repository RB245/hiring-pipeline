package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pipeline.db.SchemaFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The part of authentication that actually matters here. An audit trail that cannot say
 * who did something is half useless, so the assertion is made against the row rather
 * than against the response body: what the API says happened is not evidence of what
 * was written down.
 */
class AuditActorTest extends ApiTest {

    @Test
    void theAuthenticatedRecruiterLandsOnTheEventRow() throws Exception {
        UUID id = createCandidate("Attributed");

        mvc.perform(post("/api/v1/candidates/{id}/transitions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedCurrentStage": "APPLIED", "toStage": "SCREENING"}
                                """))
                .andExpect(status().isCreated());

        assertThat(actorsOn(id))
                .hasSize(2)
                .allSatisfy(actor -> assertThat(actor).isEqualTo(RECRUITER_ID + "/" + RECRUITER_NAME));
    }

    /** Registration writes an event too, and it must be attributed the same way. */
    @Test
    void theFirstEventIsAttributedToo() throws Exception {
        UUID id = createCandidate("First Event Attributed");

        assertThat(actorsOn(id)).containsExactly(RECRUITER_ID + "/" + RECRUITER_NAME);
    }

    private static List<String> actorsOn(UUID candidateId) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection owner = SchemaFixture.asOwner();
                PreparedStatement statement = owner.prepareStatement(
                        "SELECT actor_id, actor_name FROM stage_event WHERE candidate_id = ? ORDER BY seq")) {
            statement.setObject(1, candidateId);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    actors.add(rs.getString(1) + "/" + rs.getString(2));
                }
            }
        }
        return actors;
    }
}
