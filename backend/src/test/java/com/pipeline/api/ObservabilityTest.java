package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.pipeline.infrastructure.MaskEmails;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.filter.OncePerRequestFilter;

@Import(ObservabilityTest.LogsDuringTheRequest.class)
class ObservabilityTest extends ApiTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityTest.class);

    /**
     * Nothing in the application logs while handling a request, so the MDC has to be
     * observed from inside one. This filter is that observation point and exists only
     * here: adding an access log to production to make a test pass would be the tail
     * wagging the dog.
     */
    @TestConfiguration
    static class LogsDuringTheRequest {
        @Bean
        OncePerRequestFilter logLine() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(
                        jakarta.servlet.http.HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response,
                        jakarta.servlet.FilterChain chain)
                        throws java.io.IOException, jakarta.servlet.ServletException {
                    LoggerFactory.getLogger("request").info("handling {}", request.getRequestURI());
                    chain.doFilter(request, response);
                }
            };
        }
    }

    /**
     * The whole point of a correlation id is that the id in the log, the id in the
     * response header and the id in an error body are the same string. Asserting only
     * two of the three would leave the useful case untested.
     */
    @Test
    void logLinesAreJsonAndCarryTheCorrelationIdFromTheResponseHeader() throws Exception {
        String mine = "trace-" + UUID.randomUUID();

        List<JsonNode> lines = captureLogs(() -> {
            MvcResult result = mvc.perform(get("/api/v1/candidates/{id}", UUID.randomUUID())
                            .header(CorrelationId.HEADER, mine))
                    .andExpect(status().isNotFound())
                    .andReturn();
            assertThat(result.getResponse().getHeader(CorrelationId.HEADER)).isEqualTo(mine);
        });

        assertThat(lines).as("something was logged, and all of it parsed as JSON").isNotEmpty();
        // ECS promotes MDC entries to root fields, so the id is queryable rather than
        // buried inside the message.
        assertThat(lines).anySatisfy(line -> assertThat(line.path("correlationId").asText()).isEqualTo(mine));
    }

    @Test
    void theMdcIsClearedSoTheNextRequestDoesNotInheritAnId() throws Exception {
        mvc.perform(get("/api/v1/pipeline").header(CorrelationId.HEADER, "first-request"))
                .andExpect(status().isOk());

        List<JsonNode> lines = captureLogs(() -> log.info("outside any request"));

        assertThat(lines)
                .allSatisfy(line -> assertThat(line.path("correlationId").asText()).isNotEqualTo("first-request"));
    }

    @Test
    void emailAddressesAreMaskedOnTheWayOut() throws Exception {
        List<JsonNode> lines = captureLogs(() -> log.info("contacted priya.sharma@example.com about the offer"));

        assertThat(lines).anySatisfy(line -> {
            String message = line.get("message").asText();
            assertThat(message).doesNotContain("priya.sharma@example.com");
            assertThat(message).contains("pr***@example.com");
        });
    }

    /** Unit-level, so the edge cases are readable rather than buried in log capture. */
    @Test
    void maskingKeepsEnoughToCorrelateAndNoMore() {
        assertThat(MaskEmails.mask("a@b.com")).isEqualTo("a***@b.com");
        assertThat(MaskEmails.mask("priya.sharma@example.com")).isEqualTo("pr***@example.com");
        assertThat(MaskEmails.mask("two x@y.com and z@w.co.uk here"))
                .isEqualTo("two x***@y.com and z***@w.co.uk here");
        assertThat(MaskEmails.mask("nothing to see")).isEqualTo("nothing to see");
        assertThat(MaskEmails.mask(null)).isNull();
    }

    @Test
    void transitionsAreCountedByType() throws Exception {
        UUID id = createCandidate("Counted Transition");
        mvc.perform(post("/api/v1/candidates/{id}/transitions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedCurrentStage": "APPLIED", "toStage": "SCREENING"}
                                """))
                .andExpect(status().isCreated());

        String metrics = prometheus();

        assertThat(metrics).contains("pipeline_transitions_total");
        assertThat(metrics).contains("type=\"advanced\"");
    }

    /**
     * Not a metric this file added. Actuator and Micrometer have published it since
     * file 01, and a second latency timer would be a second number to reconcile.
     */
    @Test
    void requestLatencyIsAlreadyTimedWithoutUsAddingOne() throws Exception {
        mvc.perform(get("/api/v1/pipeline")).andExpect(status().isOk());

        assertThat(prometheus())
                .contains("http_server_requests_seconds_count")
                .contains("uri=\"/api/v1/pipeline\"");
    }

    private String prometheus() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** Swaps stdout for the duration, then parses every line the logger wrote. */
    private List<JsonNode> captureLogs(ThrowingRunnable body) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(original);
        }

        List<JsonNode> lines = new java.util.ArrayList<>();
        for (String line : captured.toString(java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) {
                lines.add(json.readTree(line));
            }
        }
        return lines;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
