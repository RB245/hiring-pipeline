# Search

Parsing is file 07 and lives in `com.pipeline.search`. This is what happens after the AST
exists: turning it into SQL, ranking what comes back, and saying something useful when it
comes back empty.

## The shape of it

```
SearchQueryParser   sentence -> AST                                  (file 07)
SpecificationBuilder AST -> Criteria predicate, composing only
FieldHandler.predicate  one field's SQL, per field
Ranking             the score expression, in SQL
JpaCandidateSearch  one query: rows, score, and a flag per condition
```

`SpecificationBuilder` composes `and`/`or`/`not` and walks to the children. It contains no
field name and no branch per field; each `FieldHandler` supplies its own predicate, so a
new searchable field is still one new class. `ArchitectureTest` enforces the half of that
claim a comment cannot.

`FieldHandler.predicate` returns a `jakarta.persistence.criteria.Predicate` rather than a
Spring `Specification<CandidateEntity>` — the same four arguments as
`Specification.toPredicate` minus the entity type parameter. Dropping the type parameter is
what keeps the JPA entity out of the search package: every column is reached by attribute
name. `MovedToField` is the one exception and imports `StageEventEntity`, because a
correlated subquery needs a class to root on and the alternative was measured to be 25×
slower (below).

## Ranking

```
score = 0.50 * nameMatch             exact 1.00, prefix 0.85, edit distance 0.80, else word_similarity
      + 0.20 * predicateSpecificity  fraction of the query's conditions this row satisfies
      + 0.20 * recency               half-life 30 days on current_stage_since
      + 0.10 * stagePriority         further along the pipeline ranks higher
```

The weights are renormalised over the terms a query actually exercises. Without text in the
query `nameMatch` is zero for every row, so on the raw weights the best possible answer to
`stage:interview in_stage_for:>7d` would score 0.43 and read as a weak match; rescaled, it
scores 0.86. The ratios between the surviving terms are untouched — only the scale changes.

`stagePriority` follows declaration order with one exception: REJECTED is the last stage
declared but is not progress, and an ordinal would float rejected candidates to the top of
every search. It is pinned to the bottom, derived through `Status` rather than named.

`predicateSpecificity` is 1.0 for every row of a plain conjunction — correctly, since those
rows all matched everything. It only separates rows under an `OR`.

Everything is computed in SQL. That is not an optimisation: ranked results are keyset-paged
on `(score, created_at, id)`, and a score computed in Java could only be applied after
fetching every matching row. It also makes "stable across runs" free.

Every result carries `matchedOn`, listing only the conditions it actually met, with the name
score quoted — `["name ~ 'sharam' (0.80)", "stage = Interview"]`.

## Fuzzy matching

`word_similarity` with the `%>` operator, threshold **0.5**, set at database level in `V9`.

The default does not work for the motivating case: `word_similarity('sharam', 'Priya
Sharma')` is 0.571 against pg_trgm's default threshold of 0.6, so `sharam` would find
nobody. Plain `similarity()` is worse at 0.25 against a 0.3 default.

Measured against 50k candidates, thresholds 0.45, 0.50 and 0.55 admit exactly the same 1667
rows for `sharam`; 0.60 collapses to 111 and 0.40 starts admitting noise (`Vikram Singh` at
0.429). The whole band behaves identically, so 0.5 sits furthest from both cliffs.

**Where the threshold is set, and why it matters.** It is a GUC, so it must be in force on
every connection that runs a search — and an unset threshold does not fail, it silently
returns nothing. `V9` sets it with `ALTER DATABASE ... SET`, so the pool, Testcontainers, a
raw JDBC fixture and a psql session debugging a plan all inherit it; Hikari's
`connection-init-sql` would have covered only the first. `SearchThresholdTest` opens a
connection outside the pool, as `pipeline_app`, and asserts the value is in force.

Two things worth knowing about the ranking that fall out of this:

- Trigram alone ranks the wrong person first. `word_similarity('sharam', 'Vikram Sharma')`
  is 0.857 against 0.571 for `Priya Sharma`. The edit-distance floor is what fixes that.
- Trigram cannot see a transposition in a short word at all. `word_similarity('pryia',
  'Priya Sharma')` is 0.333, below any usable threshold, while `levenshtein('pryia',
  'priya')` is 2. Because edit distance is a ranking tiebreak applied to rows the index
  already returned — it costs 141ms against 50k rows in a `WHERE`, versus 12ms for the
  trigram arm — `pryia` currently finds nobody. Widening the name match as an extra
  zero-result suggestion is the fix if that case ever matters.

A bare word matches the name fuzzily and the email **exactly**. Fuzzy email was measured and
rejected: `name %> term OR email LIKE '%term%'` costs 310ms against 50k rows because the
unindexed arm drags the whole OR into a sequential scan, and a trigram index on the column
does not rescue it — `email` is `citext`, so `LIKE` is the citext operator and
`gin_trgm_ops` never applies. The exact form plans to a BitmapOr across two existing
indexes.

The email comparison is wrapped in `citext(?)`. A parameter bound as `varchar` makes
Postgres resolve `citext = varchar` as an ordinary, case-sensitive text comparison, so the
column's whole purpose is quietly lost: the uncast form returns zero rows for an address
that exists.

## Zero results

A query that parsed, ran and matched nobody comes back with counts for each top-level
condition dropped in turn, best first, capped at three.

```
0 results.
  without in_stage_for:>7d   ->  6 results
  without -status:rejected   ->  2 results
```

These are computed in **one** query with a conditional aggregate per option, not one COUNT
per condition. That is cheaper than N round trips, cannot produce counts that disagree with
each other because a transition landed between two of them, and makes the cap a
presentation decision rather than a cost one.

Only top-level conjuncts are offered. Dropping a branch of an `OR` loosens nothing, and
dropping the only condition of a one-condition query is not advice.

## Query plans

All measured against 50k candidates and 152k events on `postgres:16`, with
`max_parallel_workers_per_gather = 0`, on the SQL Hibernate actually emits — captured from
the server log rather than written by hand. `ExplainPassTest` is the harness; it is inert
unless `SPIKE_DB` points at such a database.

| Query | Plan | Time |
|---|---|---|
| `stage:interview` | `candidate_stage_idx` | 44 ms |
| `stage:interview in_stage_for:>7d` | `candidate_active_since_idx` | 34 ms |
| `moved_to:interview since:monday` | `stage_event_to_stage_occurred_idx`, semi-join | 0.1 ms |
| `name:sharma` | `candidate_name_trgm_idx` | 149 ms |
| `sharam` (bare) | BitmapOr: trigram + `candidate_job_email_uq` | 237 ms |
| `reached:offer -status:hired` | sequential scan, by design | 65 ms |
| `status:active applied:<30d` | sequential scan, no index claimed | 50 ms |

Every index file 02 created for search is reached by the query shape that was supposed to
reach it. Three things are worth stating plainly:

**The name searches are dominated by scoring, not by filtering.** The indexed filter for
`name:sharma` is 16ms; the other ~130ms is `candidate_name_score` running over the 1667
matched rows at roughly 30µs each. Ranking requires scoring every match before the top-N
sort, so this is inherent rather than a missing index. Rewriting the function without its
CTE was tried and made no difference. At the scale this application actually runs — one job
opening, 200 seeded candidates — it is under a millisecond.

**`in_stage_for` must carry `AND NOT is_terminal`, and does.** Without it the planner falls
back to `candidate_stage_idx` and re-checks the date as a filter, reading 8333 rows to keep
7777. Written as `current_stage NOT IN ('HIRED','REJECTED')` it does the same thing — the
predicate prover cannot match that form to the partial index. Re-measured, both times.
`is_terminal` is mapped read-only on `CandidateEntity` for exactly this.

**`reached:` is not hot, so the enumerated-mask rewrite stays unbuilt.** File 02 left the
option open: because the pipeline is linear only about ten distinct `reached_mask` values
occur, so `reached_mask = ANY(ARRAY[...])` is btree-searchable. Measured, it works —
`ANY(ARRAY[15,31,47])` with a btree on `(job_id, reached_mask)` gives an index-only scan at
4.6ms against 10.0ms for the masked sequential scan. But 10ms is mid-pack next to 4.1ms for
`stage:` and 12ms for the trigram filter, so it is not where the time goes. Left alone.

Two SQL functions exist because Postgres **inlines** a single-expression SQL function, which
keeps the operator visible to the planner. `candidate_name_matches` wraps `%>` and still
plans to the GIN index. `candidate_reached` wraps the bitmask test, which the Criteria API
cannot express. The limits of that trick were measured too: adding an `OR` stops it inlining
(a two-armed name-or-email function costs 826ms against 24ms for the same OR written in
Criteria), and wrapping the `moved_to` EXISTS stops it becoming a semi-join (609ms against
24ms). Both are why those two live in Criteria instead.

## The unfiltered list — measured, not worth an index at this size

File 04 left `GET /api/v1/candidates` ordering by `(created_at DESC, id DESC)` with no
supporting index, deliberately, because file 02's rule is that every index names a recruiter
question and "show me everyone" barely is one. Measured:

| Rows | Index | First page | Deep page (45k in) |
|---|---|---|---|
| 200 | none | 0.15 ms | — |
| 200 | `(job_id, created_at DESC, id DESC)` | 0.12 ms | — |
| 50k | none | 16.5 ms | 7.9 ms |
| 50k | `(job_id, created_at DESC, id DESC)` | 0.19 ms | **29.3 ms** |
| 50k | index **and** row-value keyset | 0.19 ms | 0.13 ms |

At seed scale the two are indistinguishable, so the index stays out.

The interesting part is what the 50k row shows: **adding the index alone makes deep paging
3.7× worse.** `CandidateJpaRepository.pageAfter` writes the keyset comparison longhand —
`created_at < ? OR (created_at = ? AND id < ?)` — because JPQL has no row-value comparison,
and that form can only ever be a Filter, never an Index Cond. With the index present the
planner walks 45,286 buffers to find 21 rows, against 1,724 for the sequential scan it would
otherwise have used. The row-value form `(created_at, id) < (?, ?)` is an Index Cond and
costs 0.13ms flat at any depth, but needs a native query.

So the choice is not "index or no index". It is "index plus a native row-value `pageAfter`,
or neither". Neither, for now — with a tripwire recorded here, because adding the index on
its own would look like an improvement and measure as a regression.
