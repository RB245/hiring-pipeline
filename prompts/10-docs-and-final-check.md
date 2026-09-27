- 10 — Documentation, and one last test of the design

- How I want you to work (same rules as file 01)

Think before coding: state assumptions, ask when uncertain, show me the options instead of silently picking
one, push back if there's a simpler way, stop and say so if something is unclear.
Simplicity first: minimum code that solves it, nothing speculative, no abstractions for single-use code.
Surgical: touch only what you must, match existing style, don't refactor what isn't broken, only clean up
orphans your own change created.
Goal-driven: give me a numbered plan where each step names how it gets verified, then loop until those
checks pass.

- README

This gets read first, so it matters more than it feels like it should. In this order: a demo GIF at the top,
how to run it (should be `docker compose up` and one URL), an architecture diagram, the search grammar as a
reference table, the decisions I made and why, the trade-offs I knowingly accepted, and what I'd do with more
time.

For decisions, cover: why hexagonal; why a hand-written parser rather than an LLM; why the audit trail is
enforced at three levels including database grants and two separate roles; why `current_stage` is denormalised
and what keeps it honest; why `reached_mask` exists; why keyset pagination; and why rate limits are tiered by
cost rather than one global number.

Don't pad it. A reviewer would rather read eight honest paragraphs than thirty generic ones.

- What I'd do with more time

These are things I deliberately didn't build. For each, say what it is, why it wasn't worth it at this size,
and how the current design accommodates it later:

- A tamper-evident hash chain over events, each row hashing the previous row's hash, plus an `/audit/verify`
  endpoint that walks it
- Real JWT auth with login and refresh
- Multiple recruiters and multiple jobs, with proper tenancy scoping
- Playwright end-to-end tests
- k6 load testing with a real p95 number
- An outbox table for downstream events
- Monthly partitioning on `stage_event`, which the append-only design makes straightforward
- Read replica routing for the read paths
- Swapping the search adapter for OpenSearch at a scale Postgres trigram wouldn't hold. Be specific that the
  port abstraction makes that an adapter swap with no domain change; that's the actual point of the layering.

- ADRs

Short ones in `/docs/adr/`, one page each, Context / Decision / Consequences. At minimum: hexagonal
architecture; deterministic parser over LLM; append-only events enforced in the database; denormalised
projection plus `reached_mask`; keyset pagination; tiered rate limiting.

- Grammar reference

`/docs/search-grammar.md` covering every field, operator, duration form and date form, plus the full error
catalogue. Someone should be able to use the search box properly from that page alone.

- Architecture PDF

Four to six pages at `/docs/architecture.pdf`: a context diagram, a component diagram, the ERD from file 02, a
diagram of the search pipeline stages, a sequence diagram for a transition showing the transaction boundary
and the event append, a short scalability section, and the repo link. Draw the diagrams in Mermaid inside a
markdown file and render the PDF from that, so they stay version controlled rather than becoming pasted
images nobody can edit.

- CI

Harden the workflow from file 01: build, run all tests including Testcontainers, lint both sides, and fail if
coverage on the domain and search packages regresses. I'm aiming for around 85% on those two specifically and
I don't care about coverage anywhere else. Say so in the README so nobody thinks it's an oversight.

- Last thing, and this is a real test

Now add one new search field: `source:referral`, filtering on the `source` column that's been on the candidate
table since file 02.

It should take creating one new `FieldHandler` class and registering it. Nothing else. No changes to the
lexer, the parser, the validator, or the service.

If it turns out you need to touch any of those, tell me instead of quietly doing it. That means the design
failed the open/closed test, and I'd rather find out now and fix it than ship it and claim otherwise in the
README.

- Done when

The README is written, the ADRs exist, the PDF renders from committed Mermaid sources, CI is green, and
`source:referral` works having touched exactly one new file plus its registration.
