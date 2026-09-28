package com.pipeline.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pipeline.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Deliberately does not extend ApiTest, because ApiTest attaches the key to every
 * request. This is the one place that sends none.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Without this, Boot switches metrics export off for tests and /actuator/prometheus
// is not even mapped, so anything asserting on metrics would be asserting on nothing.
@AutoConfigureObservability
class UnauthenticatedApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void readingWithoutAKeyIs401InTheUsualShape() throws Exception {
        mvc.perform(get("/api/v1/pipeline"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://pipeline.example/problems/unauthenticated"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/pipeline"));
    }

    @Test
    void writingWithoutAKeyIs401() throws Exception {
        mvc.perform(post("/api/v1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Nobody\",\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWrongKeyIsAlso401() throws Exception {
        mvc.perform(get("/api/v1/candidates/{id}", UUID.randomUUID()).header("X-API-Key", "not-the-key"))
                .andExpect(status().isUnauthorized());
    }

    /** Monitoring and docs have to work without a key or they are not monitoring and docs. */
    @Test
    void healthMetricsAndDocsStayOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }
}
