package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Inconsistent error envelopes are what clients actually suffer from, so the shape is
 * asserted across every kind of failure the API can produce — including the ones Spring
 * raises itself, which are the ones that usually escape.
 */
class ProblemDetailsApiTest extends ApiTest {

    @Test
    void everyKindOfErrorCarriesTheSameFields() throws Exception {
        UUID known = createCandidate("Shape Test");
        mvc.perform(transition(known, "APPLIED", "SCREENING")).andExpect(status().isCreated());

        List<MockHttpServletRequestBuilder> failures = List.of(
                // 404: our own exception
                get("/api/v1/candidates/{id}", UUID.randomUUID()),
                // 422: a domain exception
                transition(known, "SCREENING", "HIRED"),
                // 409: an application exception
                transition(known, "APPLIED", "INTERVIEW"),
                // 400: raised by Spring, not by us
                post("/api/v1/candidates").contentType(MediaType.APPLICATION_JSON).content("{ not json"),
                // 400: bean validation, also Spring's
                post("/api/v1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\": \"\", \"email\": \"nope\"}"),
                // 400: our own, from an unusable cursor
                get("/api/v1/candidates").param("cursor", "!!!not-base64!!!"));

        for (MockHttpServletRequestBuilder failure : failures) {
            MvcResult result = mvc.perform(failure).andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());

            assertThat(result.getResponse().getStatus()).isBetween(400, 499);
            assertThat(result.getResponse().getContentType())
                    .as("RFC 9457 media type")
                    .startsWith("application/problem+json");
            for (String field : Problems.requiredFields()) {
                assertThat(body.has(field)).as("field %s in %s", field, body).isTrue();
            }
            assertThat(body.get("type").asText()).startsWith("https://pipeline.example/problems/");
            assertThat(body.get("status").asInt()).isEqualTo(result.getResponse().getStatus());
            assertThat(body.get("correlationId").asText()).isNotBlank();
        }
    }

    @Test
    void theCorrelationIdIsTheCallersWhenTheySendOneAndIsEchoedOnTheResponse() throws Exception {
        String mine = "my-trace-42";

        MvcResult result = mvc.perform(get("/api/v1/candidates/{id}", UUID.randomUUID())
                        .header(CorrelationId.HEADER, mine))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").value(mine))
                .andReturn();

        assertThat(result.getResponse().getHeader(CorrelationId.HEADER)).isEqualTo(mine);
    }

    @Test
    void aMintedCorrelationIdIsAlsoEchoed() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/candidates/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andReturn();

        String header = result.getResponse().getHeader(CorrelationId.HEADER);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(header).isNotBlank();
        assertThat(body.get("correlationId").asText()).isEqualTo(header);
    }

    @Test
    void anUnparseablePathVariableIsA400NotA500() throws Exception {
        mvc.perform(get("/api/v1/candidates/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    private MockHttpServletRequestBuilder transition(UUID id, String from, String to) {
        return post("/api/v1/candidates/{id}/transitions", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"expectedCurrentStage": "%s", "toStage": "%s"}
                        """.formatted(from, to));
    }
}
