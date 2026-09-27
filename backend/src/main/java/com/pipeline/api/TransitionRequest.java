package com.pipeline.api;

import com.pipeline.domain.Stage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TransitionRequest(
        @NotNull
                @Schema(
                        description =
                                "Where the caller last saw the candidate. If the candidate has moved since, "
                                        + "the request is rejected with 409 rather than applied to a stale view.",
                        example = "SCREENING")
                Stage expectedCurrentStage,
        @NotNull @Schema(example = "INTERVIEW") Stage toStage,
        @Size(max = 1000) String reason) {}
