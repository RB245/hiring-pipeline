package com.pipeline.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.support.IntegrationTest;
import com.pipeline.support.MutableClock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
// Without this, Boot switches metrics export off for tests and /actuator/prometheus
// is not even mapped, so anything asserting on metrics would be asserting on nothing.
@AutoConfigureObservability
@Import(ApiTest.AuthenticateEveryRequest.class)
abstract class ApiTest extends IntegrationTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected MutableClock clock;

    /**
     * The key is attached to every request rather than to each call, so the tests read
     * as though authentication were not the subject. The real filter still runs;
     * UnauthenticatedApiTest is what proves it can say no.
     */
    @TestConfiguration
    static class AuthenticateEveryRequest {
        @Bean
        MockMvcBuilderCustomizer withApiKey() {
            return builder -> builder.defaultRequest(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                            .header("X-API-Key", API_KEY));
        }
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
