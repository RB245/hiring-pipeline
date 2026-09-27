- 08 — Search, part two: execution and ranking

- How I want you to work (same rules as file 01)

Think before coding: state assumptions, ask when uncertain, show me the options instead of silently picking
one, push back if there's a simpler way, stop and say so if something is unclear.
Simplicity first: minimum code that solves it, nothing speculative, no abstractions for single-use code.
Surgical: touch only what you must, match existing style, don't refactor what isn't broken, only clean up
orphans your own change created.
Goal-driven: give me a numbered plan where each step names how it gets verified, then loop until those
checks pass.

- Turning the AST into SQL

A `SpecificationBuilder` walks the AST and produces a JPA `Specification<Candidate>`. Each `FieldHandler`
returns its own Specification and the builder only composes them with and/or/not. No field-specific logic in
the builder itself, otherwise the extensibility from file 07 is fake.

Roughly how each field should land:

 
stage:         compares current_stage
reached:       bitmask test on reached_mask, not a join
moved_to:      EXISTS against stage_event on (to_stage, occurred_at), with since/before as bounds
in_stage_for:  compares current_stage_since against the injected clock
status:        maps to the terminal stages, with active meaning not terminal
name: / bare   pg_trgm similarity with unaccent, levenshtein as a tiebreak for short strings
 

Check the query plans. If `reached:offer` or `stage:interview in_stage_for:>7d` isn't using the indexes from
file 02, tell me, because that means either the index set or the query shape is wrong.

While you're in there, settle one thing left open in file 04. The unfiltered candidate list orders by
`(created_at DESC, id DESC)` with no supporting index, deliberately, because file 02's rule is that every
index names a recruiter question and "show me everyone" barely is one. Measure it at seed scale and at 50k
and tell me the numbers. I'd rather the README said "measured, not worth it at this size" than quietly carry
an index nobody justified — but if the sort is actually hurting, say so and add it.

- Fuzzy matching

Pick a trigram similarity threshold and write down in a comment what you picked and why. Make sure `sharam`
actually finds `Priya Sharma`. I'd rather have a slightly loose threshold with good ranking than a tight one
that drops typos, because missing the person entirely is the worse failure.

Carry-over from file 02, so you don't rediscover this the hard way. Plain `similarity()` does not work for the
motivating case: `'sharam' % 'Priya Sharma'` scores 0.25, under the 0.3 default, so it matches nothing. The
measured answer is the word-similarity operator `%>` with `pg_trgm.word_similarity_threshold` at 0.5, which
scores 0.571 and still uses the GIN index. The numbers are in V5's comments.

What isn't settled is where that threshold gets set, and I want you to decide explicitly and tell me which you
chose. It's a GUC, so it has to be in force on every connection that runs a search. Setting it at database
level in a migration means every connection inherits it including Testcontainers; setting it in the pool's
init SQL keeps it in application config but has to survive connection recycling. What I don't want is it being
assumed, because an unset threshold makes search silently return nothing rather than error, which is the worst
failure mode this feature could have. Write a test that proves the threshold is in force on a freshly opened
connection.

One more from file 02. The btree on `reached_mask` was removed because `&` isn't a searchable operator, so
`reached:offer` is currently a seq scan (measured at 8.4ms against 50k rows, versus 11.3ms for the equivalent
EXISTS pair). Fine for now. But note the option that wasn't considered: because the pipeline is linear, only
about ten distinct `reached_mask` values ever occur, so `reached_mask = ANY(ARRAY[...])` over the enumerated
matching masks is btree-searchable and would get an index scan. If `reached:` predicates show up hot in your
EXPLAIN pass, that's the move. If they don't, leave it alone and say so.

- Ranking

Best matches first, and I want the formula written down rather than left to vibes:

 
score = 0.50 * nameMatch             exact 1.0, prefix 0.85, else trigram similarity, levenshtein <= 2 counts
      + 0.20 * predicateSpecificity  how strongly the predicates are satisfied
      + 0.20 * recency               decayed by days since the last stage_event
      + 0.10 * stagePriority         further along the pipeline ranks higher
 

Each result returns its `score` and a `matchedOn` array explaining itself, like
`["name ~ 'Sharma' (0.82)", "stage = Interview"]`. Explainable ranking is worth more to me here than a
marginally better ranking nobody can reason about.

If you think these weights are wrong, argue with me before implementing them. But don't change them silently.

- Zero results

This is the part I care most about and the bit people skip. If a query parses fine but returns nothing, don't
just return an empty array. Re-run it with each predicate dropped in turn, count the rows, and return those
counts as suggestions:

 
0 results.
  without in_stage_for:>7d   ->  6 results
  without -status:rejected   ->  2 results
 

They're cheap COUNT queries over subsets of the predicate set. Cap it so a 12-predicate query doesn't fire 12
counts; three or four suggestions is plenty, take the ones that unlock the most results.

- Endpoints

 
GET /api/v1/search/explain?q=    returns normalised DSL, the AST, and resolved dates and durations
GET /api/v1/search/suggest?q=    autocomplete over field names and stage values
 

`/explain` is partly for the UI, partly because it makes the whole feature testable, and partly so I can show
a reviewer exactly how a sentence got interpreted.

Then wire `q=` into `GET /api/v1/candidates`, reusing the keyset pagination from file 04, and put it on the
60/min search bucket created in file 06.

- Don't build

No caching of search results. No query history or saved searches. No OpenSearch, no materialised views. If
Postgres trigram stops being enough it's an adapter swap later, and that belongs in the README not the code.

- Done when

All eight acceptance queries from file 07 return correct results against the seed data. Ranking is stable
across runs and every result explains why it matched. A query with zero results comes back with usable
relaxation suggestions. `/explain` shows the parse for each of the eight. All of it tested through
Testcontainers with a fixed clock, so "since Monday" means something deterministic.
