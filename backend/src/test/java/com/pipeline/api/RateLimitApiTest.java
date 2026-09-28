package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Small limits rather than the production ones, so a burst is a handful of requests
 * rather than hundreds. What is under test is the filter, not the number.
 *
 * <p>Written as one long test on purpose. There is a single valid API key, so every
 * request in this class draws on the same bucket; split across methods these
 * assertions would depend on execution order. Per-key isolation is covered at the port
 * instead, in {@code RateLimiterTest}, where keys can actually differ.
 */
@TestPropertySource(
        properties = {
            "pipeline.rate-limit.write=3",
            "pipeline.rate-limit.read=5",
            "pipeline.rate-limit.window=1m"
        })
class RateLimitApiTest extends ApiTest {

    @Test
    void aBurstPastTheWriteLimitIsRefusedAndTheRestOfTheApiStillWorks() throws Exception {
        for (int i = 1; i <= 3; i++) {
            MvcResult allowed = create();
            assertThat(allowed.getResponse().getStatus()).as("request %d", i).isEqualTo(201);
            assertThat(allowed.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("3");
            // Headers on success too: a client that only learns the limit by breaching
            // it has no way to avoid breaching it.
            assertThat(allowed.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo(String.valueOf(3 - i));
        }

        MvcResult refused = create();

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(refused.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(Long.parseLong(refused.getResponse().getHeader("X-RateLimit-Reset"))).isNotNegative();
        assertThat(Long.parseLong(refused.getResponse().getHeader("Retry-After"))).isPositive();

        // Same envelope as every other error in the API.
        JsonNode body = json.readTree(refused.getResponse().getContentAsString());
        assertThat(refused.getResponse().getContentType()).startsWith("application/problem+json");
        for (String field : Problems.requiredFields()) {
            assertThat(body.has(field)).as("field %s", field).isTrue();
        }
        assertThat(body.get("type").asText()).isEqualTo("https://pipeline.example/problems/rate-limited");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("correlationId").asText()).isNotBlank();

        // Separate buckets: exhausting writes must not stop her looking at the board.
        MvcResult read = mvc.perform(get("/api/v1/pipeline")).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(read.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("5");

        String metrics = mvc.perform(get("/actuator/prometheus"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(metrics).contains("pipeline_rate_limit_rejections_total");
        assertThat(metrics).contains("tier=\"write\"");
    }

    @Test
    void healthAndMetricsAreNotRateLimitedAtAll() throws Exception {
        MvcResult health = mvc.perform(get("/actuator/health")).andReturn();

        assertThat(health.getResponse().getStatus()).isEqualTo(200);
        assertThat(health.getResponse().getHeader("X-RateLimit-Limit")).isNull();
    }

    private MvcResult create() throws Exception {
        return mvc.perform(post("/api/v1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Burst Test", "email": "%s@example.com"}
                                """.formatted(UUID.randomUUID())))
                .andReturn();
    }
}
