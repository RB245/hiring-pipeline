package com.pipeline.application;

import java.util.List;

/** {@code next} is null on the last page. */
public record CandidatePage(List<CandidateSummary> candidates, Cursor next) {}
