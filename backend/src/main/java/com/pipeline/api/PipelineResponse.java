package com.pipeline.api;

import java.util.List;

public record PipelineResponse(List<BoardColumnResponse> columns) {}
