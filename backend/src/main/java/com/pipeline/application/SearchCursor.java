package com.pipeline.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Keyset position for a ranked result set: the same idea as {@link Cursor}, one column
 * deeper.
 *
 * <p>The score has to be part of the key because it is what the rows are ordered by, and
 * {@code (createdAt, id)} is still underneath it because two candidates can score
 * identically — six people called Sharma match "sharam" equally well — and a cursor over a
 * non-unique key would skip or repeat rows at the page boundary.
 *
 * <p>Comparing a double for equality is safe here in a way it usually is not: the score is
 * a deterministic function of the row and the query, computed by the database both times,
 * so the value that comes back in a cursor is bit-for-bit the value the next page compares
 * against. It is arithmetic being repeated, not re-derived.
 */
public record SearchCursor(double score, Instant createdAt, UUID id) {}
