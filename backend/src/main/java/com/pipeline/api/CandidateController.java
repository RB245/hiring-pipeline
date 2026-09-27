package com.pipeline.api;

import com.pipeline.application.CandidateNotFoundException;
import com.pipeline.application.CandidatePage;
import com.pipeline.application.CandidateProfile;
import com.pipeline.application.CandidateReader;
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.EventReader;
import com.pipeline.application.JobReader;
import com.pipeline.application.NoJobConfiguredException;
import com.pipeline.application.RegisterCandidate;
import com.pipeline.application.TransitionCandidate;
import com.pipeline.application.TransitionOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/candidates")
@Validated
@Tag(name = "Candidates")
class CandidateController {

    private final RegisterCandidate registerCandidate;
    private final TransitionCandidate transitionCandidate;
    private final CandidateReader candidates;
    private final EventReader events;
    private final JobReader jobs;
    private final CurrentActor actor;
    private final Clock clock;

    CandidateController(
            RegisterCandidate registerCandidate,
            TransitionCandidate transitionCandidate,
            CandidateReader candidates,
            EventReader events,
            JobReader jobs,
            CurrentActor actor,
            Clock clock) {
        this.registerCandidate = registerCandidate;
        this.transitionCandidate = transitionCandidate;
        this.candidates = candidates;
        this.events = events;
        this.jobs = jobs;
        this.actor = actor;
        this.clock = clock;
    }

    @PostMapping
    @Operation(summary = "Register a candidate, recording their APPLIED event")
    ResponseEntity<CandidateResponse> create(@Valid @RequestBody CreateCandidateRequest request) {
        UUID jobId = jobs.singleJobId().orElseThrow(NoJobConfiguredException::new);
        UUID id = registerCandidate.register(
                new CandidateProfile(jobId, request.fullName(), request.email(), request.phone(), request.source()),
                actor.get());
        return ResponseEntity.created(URI.create("/api/v1/candidates/" + id)).body(read(id));
    }

    @GetMapping
    @Operation(summary = "List candidates, newest first, paged by cursor rather than offset")
    CandidatePageResponse list(
            @Parameter(description = "Opaque token from a previous page's nextCursor") @RequestParam(required = false)
                    String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        UUID jobId = jobs.singleJobId().orElseThrow(NoJobConfiguredException::new);
        CandidatePage page = candidates.page(jobId, cursor == null ? null : CursorCodec.decode(cursor), limit);
        return new CandidatePageResponse(
                page.candidates().stream().map(summary -> CandidateResponse.of(summary, clock)).toList(),
                page.next() == null ? null : CursorCodec.encode(page.next()));
    }

    @GetMapping("/{id}")
    CandidateResponse get(@PathVariable UUID id) {
        return read(id);
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "The candidate's full history, ascending by seq. Append-only.")
    List<StageEventResponse> timeline(@PathVariable UUID id) {
        if (candidates.summary(id).isEmpty()) {
            throw new CandidateNotFoundException(id);
        }
        return events.timeline(id).stream().map(StageEventResponse::of).toList();
    }

    @PostMapping("/{id}/transitions")
    @Operation(
            summary = "Move a candidate one stage",
            description =
                    """
                    The only endpoint that mutates pipeline state.

                    Supply Idempotency-Key to make retries safe. A repeat of a key already seen for this
                    candidate returns the original event and writes nothing; the response is byte-for-byte
                    what the first call returned, including the 201.

                    expectedCurrentStage is optimistic concurrency. If the candidate has moved since the
                    caller last read them, the request is refused with 409 and nothing is written. Two
                    simultaneous transitions on one candidate resolve the same way: one wins, the other
                    gets 409 and should re-read before retrying.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Event recorded, or replayed from a matching key"),
        @ApiResponse(responseCode = "409", description = "Stale expectedCurrentStage, or lost a concurrent race"),
        @ApiResponse(responseCode = "422", description = "Move is not legal; body lists the moves that are")
    })
    ResponseEntity<StageEventResponse> transition(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, description = "Repeat to retry safely; any stable unique string")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody TransitionRequest request) {

        TransitionOutcome outcome = transitionCandidate.transition(
                id,
                request.expectedCurrentStage(),
                request.toStage(),
                actor.get(),
                request.reason(),
                idempotencyKey);

        return ResponseEntity.created(URI.create("/api/v1/candidates/" + id + "/events"))
                .body(StageEventResponse.of(outcome.event()));
    }

    private CandidateResponse read(UUID id) {
        CandidateSummary summary = candidates.summary(id).orElseThrow(() -> new CandidateNotFoundException(id));
        return CandidateResponse.of(summary, clock);
    }
}
