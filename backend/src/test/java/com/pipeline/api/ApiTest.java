package com.pipeline.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.support.IntegrationTest;
import com.pipeline.support.MutableClock;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
abstract class ApiTest extends IntegrationTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void seedTheJob() throws SQLException {
        ensureCanonicalJob();
    }

    /** Creates a candidate through the API and returns its id. */
    protected UUID createCandidate(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"fullName": "%s", "email": "%s@example.com", "source": "referral"}
                                """
                                        .formatted(name, UUID.randomUUID())))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }
}
