package com.pipeline.api;

import com.pipeline.application.CandidateReader;
import com.pipeline.application.JobReader;
import com.pipeline.application.NoJobConfiguredException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pipeline")
@Tag(name = "Pipeline")
class PipelineController {

    private final CandidateReader candidates;
    private final JobReader jobs;
    private final Clock clock;

    PipelineController(CandidateReader candidates, JobReader jobs, Clock clock) {
        this.candidates = candidates;
        this.jobs = jobs;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "The board: every candidate grouped by stage, empty columns included")
    PipelineResponse board() {
        UUID jobId = jobs.singleJobId().orElseThrow(NoJobConfiguredException::new);
        return new PipelineResponse(candidates.board(jobId).stream()
                .map(column -> new BoardColumnResponse(
                        column.stage(),
                        column.count(),
                        column.candidates().stream()
                                .map(summary -> CandidateResponse.of(summary, clock))
                                .toList()))
                .toList());
    }
}
