package com.pipeline.application;

import java.util.Optional;
import java.util.UUID;

/**
 * There is one job opening, so no endpoint carries a job id. This resolves it rather
 * than making every caller pass something there is only one of.
 */
public interface JobReader {

    Optional<UUID> singleJobId();
}
