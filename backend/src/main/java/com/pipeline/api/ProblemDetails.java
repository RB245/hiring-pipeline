package com.pipeline.api;

import com.pipeline.application.CandidateNotFoundException;
import com.pipeline.application.NoJobConfiguredException;
import com.pipeline.application.StaleCandidateStateException;
import com.pipeline.domain.IllegalStageTransitionException;
import com.pipeline.domain.Stage;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error in the API is shaped here, so a client can rely on one envelope whatever
 * went wrong. Extends the Spring handler rather than sitting beside it, because the
 * framework's own failures — unreadable body, wrong path variable type, failed bean
 * validation — have to come out of the same mould as ours or the consistency is a lie.
 */
@RestControllerAdvice
class ProblemDetails extends ResponseEntityExceptionHandler {

    private static final String BASE = "https://pipeline.example/problems/";

    @ExceptionHandler(IllegalStageTransitionException.class)
    ResponseEntity<ProblemDetail> illegalTransition(IllegalStageTransitionException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "illegal-transition", "Illegal transition",
                e.getMessage(), request);
        problem.setProperty("fromStage", e.from());
        problem.setProperty("toStage", e.to());
        // The whole reason this error is typed: the board has to be able to say what
        // she can do instead, not merely that she cannot do this.
        problem.setProperty("legalTargets", e.legalTargets().stream().map(Stage::name).toList());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(CandidateNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(CandidateNotFoundException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem(HttpStatus.NOT_FOUND, "candidate-not-found", "Candidate not found",
                        e.getMessage(), request));
    }

    @ExceptionHandler(StaleCandidateStateException.class)
    ResponseEntity<ProblemDetail> stale(StaleCandidateStateException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "stale-candidate-state", "Candidate has moved on",
                e.getMessage(), request);
        problem.setProperty("expectedCurrentStage", e.expected());
        problem.setProperty("actualCurrentStage", e.actual());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Both of these mean the same thing to a client: somebody else got there first.
     * Optimistic locking catches the common case; the unique indexes on seq and on the
     * idempotency key catch the rest.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ResponseEntity<ProblemDetail> lostTheRace(RuntimeException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(problem(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent modification",
                        "The candidate was modified by another request. Re-read and try again.", request));
    }

    @ExceptionHandler(MalformedCursorException.class)
    ResponseEntity<ProblemDetail> badCursor(MalformedCursorException e, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "malformed-cursor", "Malformed cursor",
                        e.getMessage(), request));
    }

    @ExceptionHandler(NoJobConfiguredException.class)
    ResponseEntity<ProblemDetail> noJob(NoJobConfiguredException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "no-job-configured", "No job opening",
                        e.getMessage(), request));
    }

    /**
     * Catches everything Spring raises itself and re-stamps it, so a malformed body or
     * an unparseable path variable carries the same fields as our own errors.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        ProblemDetail problem = problem(
                status,
                slugFor(status),
                HttpStatus.valueOf(status.value()).getReasonPhrase(),
                exception.getMessage(),
                servletRequest);

        if (body instanceof ProblemDetail existing && existing.getProperties() != null) {
            existing.getProperties().forEach(problem::setProperty);
        }
        return ResponseEntity.status(status).body(problem);
    }

    private static String slugFor(HttpStatusCode status) {
        return status.value() == HttpStatus.BAD_REQUEST.value() ? "invalid-request" : "request-failed";
    }

    private static ProblemDetail problem(
            HttpStatusCode status, String slug, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        problem.setType(URI.create(BASE + slug));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", CorrelationId.current(request));
        return problem;
    }

    /** Kept only so the field list below is visible in one place for the shape test. */
    static List<String> requiredFields() {
        return List.of("type", "title", "status", "detail", "instance", "correlationId");
    }
}
