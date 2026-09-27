package com.pipeline.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CandidatePageResponse(
        List<CandidateResponse> candidates,
        @Schema(description = "Pass back as ?cursor= for the next page. Null on the last page.")
                String nextCursor) {}
