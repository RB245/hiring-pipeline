package com.pipeline.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * One place errors are shaped. Extracted because rate limiting and authentication both
 * fail in servlet filters, outside any {@code @RestControllerAdvice}, and an error
 * envelope that only applies once the request reaches a controller is not a contract.
 */
@Component
public class Problems {

    static final String BASE = "https://pipeline.example/problems/";

    private final ObjectMapper json;

    Problems(ObjectMapper json) {
        this.json = json;
    }

    public ProblemDetail of(
            HttpStatusCode status, String slug, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        problem.setType(URI.create(BASE + slug));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", CorrelationId.current(request));
        return problem;
    }

    /** For the filters, which have to write the body themselves. */
    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(asJson(problem));
    }

    private String asJson(ProblemDetail problem) {
        try {
            return json.writeValueAsString(problem);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A ProblemDetail that will not serialise", e);
        }
    }

    /** Kept so the field list is visible in one place for the shape test. */
    public static List<String> requiredFields() {
        return List.of("type", "title", "status", "detail", "instance", "correlationId");
    }
}
