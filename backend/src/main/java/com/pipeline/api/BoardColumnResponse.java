package com.pipeline.api;

import com.pipeline.domain.Stage;
import java.util.List;

public record BoardColumnResponse(Stage stage, int count, List<CandidateResponse> candidates) {}
