# ADR 0005 — Keyset pagination, not OFFSET

Status: accepted

## Context

The candidate list and the search results both page. `LIMIT ... OFFSET ...` is the obvious
implementation and is wrong in a way that is easy to miss in testing: a row inserted while
the recruiter is between pages shifts everything down, so page two re-shows a row she has
already seen, or skips one she never did.

For a pipeline where new candidates arrive continuously, that is not a rare edge case.

## Decision

The page is defined by where the last one ended, not by how many rows to skip.

The list orders by `(created_at DESC, id DESC)`; `id` breaks ties so the ordering is total
even when two candidates were created in the same instant. The cursor is that pair,
base64-encoded so clients treat it as an opaque token rather than something to construct.
The timestamp is encoded at full precision — truncating to milliseconds would let a row
sharing the truncated instant be skipped or repeated at a page boundary.

A ranked search needs one column more: `(score, created_at, id)`. Comparing a double for
equality is safe here in a way it usually is not, because the score is a deterministic
function of the row and the query, computed by the database both times — it is arithmetic
being repeated, not re-derived. `(created_at, id)` stays underneath it because candidates
genuinely do tie: six people called Sharma score identically against "sharam", and a
cursor over a non-unique key would skip or repeat at the boundary.

The two cursor kinds are tagged differently, so feeding a list cursor to a search fails as
a malformed cursor rather than silently paging through the wrong ordering.

## Consequences

Pages are stable under concurrent inserts, and a deep page costs the same as a shallow one
— there is no growing prefix to count past.

One measured trap, recorded because the fix looks like an improvement and measures as a
regression. JPQL has no row-value comparison, so `pageAfter` writes the keyset longhand as
`created_at < ? OR (created_at = ? AND id < ?)`. That form can only ever be a `Filter`,
never an `Index Cond`. Adding the obvious supporting index at 50k rows makes the first
page 87× faster and a deep page **3.7× slower** — 45,286 buffers against 1,724 — because
the planner now walks the index and filters every entry. The row-value form
`(created_at, id) < (?, ?)` *is* an index condition and is flat at any depth, but needs a
native query.

So the choice is not "index or no index". It is "index plus a native row-value
`pageAfter`, or neither". At seed scale the two are indistinguishable (0.15 ms against
0.12 ms), so it is neither — with the numbers in `docs/search.md` so that nobody adds the
index on its own and makes deep paging worse while believing they improved it.

The cost is that a client cannot jump to page 7. Nothing in this product wants to.
