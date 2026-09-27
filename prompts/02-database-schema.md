- 02 — Database schema

- How I want you to work (same rules as file 01)

Think before coding: state assumptions, ask when uncertain, show me the options instead of silently picking
one, push back if there's a simpler way, stop and say so if something is unclear.
Simplicity first: minimum code that solves it, nothing speculative, no abstractions for single-use code.
Surgical: touch only what you must, match existing style, don't refactor what isn't broken, only clean up
orphans your own change created.
Goal-driven: give me a numbered plan where each step names how it gets verified, then loop until those
checks pass.

- What I need

The full schema as Flyway migrations. One concern per migration, roughly: extensions, enums, tables, indexes,
the immutability trigger, and the database roles. Put comments in the SQL on anything non-obvious, explaining
why rather than what.

Timestamps are `timestamptz` and the app runs in UTC. Prefer `text` over `varchar`. Snake case, singular
table names.

- Extensions

`pg_trgm` and `unaccent` for fuzzy name matching, `fuzzystrmatch` for levenshtein as a tiebreak, `citext` for
case-insensitive emails.

- Tables

A `job` table, trivially id, title, created_at.

A `candidate` table with id (uuid), job_id, full_name, email (citext), phone, source, created_at, and a
`version` column for JPA optimistic locking. Unique on (job_id, email).

A `stage_event` table with id (bigserial), candidate_id, seq, from_stage, to_stage, event_type, occurred_at,
actor_id, actor_name, reason, idempotency_key.

`seq` is per-candidate and starts at 1, unique on (candidate_id, seq). `from_stage` is null only on the very
first event, so add a check constraint enforcing exactly that against seq, and another that from_stage and
to_stage are never equal. `idempotency_key` gets a unique index on (candidate_id, idempotency_key) so a
retried or double-clicked transition can't write a second event. Make it partial on `idempotency_key IS NOT
NULL`, and tell me if you think that's redundant given how Postgres treats nulls in unique indexes.

Stages are APPLIED, SCREENING, INTERVIEW, OFFER, HIRED, REJECTED. Event types are APPLIED, ADVANCED,
REJECTED, HIRED. Both as Postgres enums.

- Three denormalised columns on candidate

Push back if you disagree with any of these.

`current_stage` and `current_stage_since` are a projection of the event log, written in the same transaction
as the event. The log stays the source of truth; these exist so the board query and the "stuck for more than a
week" query aren't aggregating events every time.

`reached_mask` is a smallint bitmask of every stage a candidate has ever entered (Applied 1, Screening 2,
Interview 4, Offer 8, Hired 16, Rejected 32), so "who reached Offer but didn't get hired" becomes an index
scan instead of a semi-join against the event table.

`is_terminal` is a generated stored column off current_stage, for partial indexing.

- Indexes

I don't want a generic set. Each index should exist for a specific question the recruiter asks, with that
question in a comment above it. The questions are: who's in Interview right now; who's been stuck in Screening
for more than a week; who moved to Interview since Monday; who reached Offer but wasn't hired; show me one
candidate's timeline; and find "Priya Sharma" when I typed "sharam". Propose the index set from those, and
justify anything you'd add beyond them.

- Immutability, which is the part I care most about

Three independent layers so one mistake can't undo it.

1. A `BEFORE UPDATE OR DELETE` trigger on `stage_event` that raises an exception.
2. Two database roles. A migration role that owns the schema and has DDL, and an application role that Spring
   connects as, holding only SELECT and INSERT on `stage_event`, with UPDATE and DELETE explicitly revoked.
   They must be separate, otherwise the app could just drop the trigger and the revoke means nothing.
3. No `ON DELETE CASCADE` pointing at `stage_event`, and none off `candidate` either, since deleting a
   candidate would destroy history. If deletion is ever genuinely needed it should be a flag, not a DELETE.

- Also give me

A Mermaid ERD I can commit into `/docs`. A short note on what happens when we eventually need to add a stage,
given Postgres enums can gain values but not be reordered, and whether you'd still choose an enum over a
lookup table here. And an assessment of whether the projection columns can drift, and what you'd do about it.

- Done when

Flyway migrates cleanly from empty. A Testcontainers test connects as the application role, attempts UPDATE
and then DELETE on `stage_event`, and asserts both fail. Another test inserts a violating row for each check
constraint and asserts each is rejected. Use real Postgres, not H2, because most of this is Postgres-specific
and H2 would let broken things pass.
