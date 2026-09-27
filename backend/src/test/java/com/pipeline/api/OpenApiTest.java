package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class OpenApiTest extends ApiTest {

    @Test
    void everyEndpointIsDocumented() throws Exception {
        MvcResult result = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode paths = json.readTree(result.getResponse().getContentAsString()).get("paths");

        assertThat(paths.has("/api/v1/candidates")).isTrue();
        assertThat(paths.has("/api/v1/candidates/{id}")).isTrue();
        assertThat(paths.has("/api/v1/candidates/{id}/events")).isTrue();
        assertThat(paths.has("/api/v1/candidates/{id}/transitions")).isTrue();
        assertThat(paths.has("/api/v1/pipeline")).isTrue();
        assertThat(paths.has("/api/v1/admin/rebuild-projections")).isTrue();
    }

    /** The two things a client integrating with this would get wrong. */
    @Test
    void theIdempotencyHeaderAndThe409AreDocumentedOnTheTransitionEndpoint() throws Exception {
        MvcResult result = mvc.perform(get("/v3/api-docs")).andReturn();
        JsonNode transition = json.readTree(result.getResponse().getContentAsString())
                .get("paths")
                .get("/api/v1/candidates/{id}/transitions")
                .get("post");

        assertThat(transition.get("parameters").toString()).contains("Idempotency-Key");
        assertThat(transition.get("responses").has("409")).isTrue();
        assertThat(transition.get("responses").get("409").get("description").asText())
                .containsIgnoringCase("stale");
        assertThat(transition.get("description").asText())
                .contains("Idempotency-Key")
                .contains("expectedCurrentStage");
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    @Test
    void theApiIsTitled() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.info.title").value("Hiring pipeline"))
                .andExpect(jsonPath("$.info.version").value("v1"));
    }
}
