-- Every index below answers a question the recruiter actually asks, and every one was
-- checked with EXPLAIN against 50k seeded candidates to confirm the planner picks it.
-- One candidate index was dropped during that exercise; see the note further down.


-- "Who is in Interview right now?"
--
-- Not for the board itself: rendering the whole board reads every candidate for the
-- job regardless, so no index helps it. This earns its place on the single-stage
-- filter, where it turns a scan of the job into a scan of one column of the board.
CREATE INDEX candidate_stage_idx ON candidate (job_id, current_stage);

-- "Who has been stuck in Screening for more than a week?"
--
-- current_stage_since trails the stage in the key so the cutoff becomes part of the
-- index condition rather than a filter applied afterwards. Partial because the
-- question is only ever asked about people still moving: time-in-stage is meaningless
-- once someone is hired or rejected.
--
-- The query MUST carry an explicit "AND NOT is_terminal". Without it the planner
-- cannot prove the query implies this index's predicate, silently falls back to
-- candidate_stage_idx, and re-checks the date as a filter. That was measured, not
-- assumed. This is the reason is_terminal exists as a stored column at all: the same
-- predicate written as current_stage NOT IN ('HIRED','REJECTED') is beyond what the
-- planner's predicate prover will match.
CREATE INDEX candidate_active_since_idx ON candidate (job_id, current_stage, current_stage_since)
    WHERE NOT is_terminal;

-- "Who reached Offer but was not hired?"  -> (reached_mask & 8) = 8 AND (reached_mask & 16) = 0
--
-- Deliberately not indexed. & is not a searchable operator, so a btree on
-- (job_id, reached_mask) is never chosen; adding INCLUDE (id) to tempt an index-only
-- scan did not change the plan either. Both were tried and the planner seq-scanned
-- regardless.
--
-- An expression index on ((reached_mask & 24)) IS used, but it answers exactly one
-- reached/not-reached pair, and the search work needs arbitrary pairs. Fifteen
-- single-purpose indexes is a worse trade than one sequential scan.
--
-- reached_mask still earns its place, just not for the reason an index would suggest.
-- Measured on 50k candidates: masked predicate 8.4ms, equivalent EXISTS/NOT EXISTS
-- pair against stage_event 11.3ms. The gap is modest here because the seed averages
-- under two events per candidate; it widens as histories grow, since the mask query is
-- unaffected by event volume and the semi-join is not. The larger win is that it keeps
-- the search layer writing a predicate on one row instead of generating join pairs.

-- "Find Priya Sharma when I typed sharam."
--
-- Trigram GIN over the accent-folded name. unaccent() cannot appear here directly,
-- hence the immutable wrapper from V2.
--
-- Two things the search layer needs to know, both measured against this index:
-- the plain % operator at its default 0.3 threshold does NOT match "sharam" against
-- "Priya Sharma" (similarity 0.25), because similarity is diluted by the rest of the
-- string. The word-similarity operator %> does match it (0.571) once
-- pg_trgm.word_similarity_threshold is lowered to 0.5, and the planner uses this same
-- index for it. Accent folding works through the wrapper: "muller" finds "Zoë Müller".
--
-- fuzzystrmatch's levenshtein is intentionally not indexed: it is the tiebreak applied
-- to the handful of rows trigram already returned. levenshtein('sharam','sharma') = 2.
CREATE INDEX candidate_name_trgm_idx ON candidate USING gin (immutable_unaccent(full_name) gin_trgm_ops);

-- "Who moved to Interview since Monday?"
--
-- Answered from the log rather than from candidate, because the projection only knows
-- where someone is now, not that they passed through Interview on Tuesday and were
-- rejected on Thursday.
CREATE INDEX stage_event_to_stage_occurred_idx ON stage_event (to_stage, occurred_at);

-- "Show me one candidate's timeline."
--
-- No index here on purpose: stage_event_candidate_seq_uq in V4 is already a btree on
-- (candidate_id, seq), which is this lookup in this order, and EXPLAIN confirms it is
-- what gets used. The same is true of the FK column itself, and of
-- candidate_job_email_uq for email lookup.

-- Retry safety for transitions.
--
-- The partial predicate is not what enforces this. Postgres indexes are NULLS DISTINCT
-- by default, so a plain unique index already permits any number of keyless events per
-- candidate. WHERE ... IS NOT NULL keeps those rows out of the index entirely and
-- states the intent, so that if anyone later reaches for NULLS NOT DISTINCT the
-- keyless events do not suddenly start colliding with each other.
CREATE UNIQUE INDEX stage_event_idempotency_uq ON stage_event (candidate_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
