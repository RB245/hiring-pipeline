# ADR 0004 — A denormalised projection, and `reached_mask`

Status: accepted

## Context

`stage_event` is the source of truth, and the two questions the product exists to answer
are awkward against it:

- *Where is this candidate now?* — the last event, per candidate.
- *Who has been stuck in Screening for over a week?* — the last event, for everyone,
  filtered and compared against now.

Both are window functions or correlated subqueries over the whole log. Rendering a
six-column board would aggregate the entire event table on every page load.

A third question is worse: *who reached Offer but was never hired?* That is two existence
checks over the log per candidate, and the log grows without bound.

## Decision

Three columns on `candidate`, written in the same transaction as the event that moves it:

- `current_stage`, `current_stage_since` — where they are and since when.
- `reached_mask` — a bitfield of every stage ever entered. APPLIED 1, SCREENING 2,
  INTERVIEW 4, OFFER 8, HIRED 16, REJECTED 32.

The bits are declared on the `Stage` enum rather than derived from the ordinal, so
inserting a stage mid-pipeline cannot silently renumber every mask already stored.

`is_terminal` is a generated column, `current_stage IN ('HIRED','REJECTED')`. It exists so
partial indexes can say `WHERE NOT is_terminal` — Postgres can prove a query implies that
predicate, and cannot prove the same of the `NOT IN` form written out longhand.

What keeps it honest: the write is one transaction with the event, so the two cannot
diverge through a failure. And `POST /api/v1/admin/rebuild-projections` recomputes every
candidate's columns from the log and reports how many differed. That number is the drift
figure; it should always be zero, and the endpoint is safe to run at any time because the
log is immutable and this only rewrites the cache of it.

## Consequences

The board is one indexed read. "Stuck in Screening" is a bitmap index scan on
`candidate_active_since_idx`, 34 ms against 50k rows.

`reached:offer` is a sequential scan and stays one: `&` is not a searchable operator, so a
btree on `(job_id, reached_mask)` is never chosen by the planner. Measured at 10.0 ms
against 50k, which is mid-pack next to 4.1 ms for `stage:` and 149 ms for a name search,
so it is not where the time goes. The mask still earns its place — it beats the equivalent
`EXISTS` pair (8.4 ms against 11.3 ms) and, more importantly, the gap widens as histories
grow, because the mask is one row per candidate however many events they accumulate.

An alternative was measured and deliberately not taken. Because the pipeline is linear
only about ten distinct mask values ever occur, so `reached_mask = ANY(ARRAY[...])` over
the enumerated matching values *is* btree-searchable and gives an index-only scan at
4.6 ms. It is not built because 10 ms is not the bottleneck; it is written down in
`docs/search.md` as the move if `reached:` ever shows up hot.

The cost is a second copy of a fact. That is the trade denormalisation always is; what
makes it acceptable is that the copy is written transactionally and can be rebuilt from
the original on demand.
