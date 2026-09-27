package com.pipeline.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Keyset position, not an offset. The id breaks ties so the ordering is total even when
 * two candidates were created in the same instant. Encoding it into something opaque
 * for clients is the HTTP layer's job.
 */
public record Cursor(Instant createdAt, UUID id) {}
