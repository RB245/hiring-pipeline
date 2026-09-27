- 04 — Persistence

- How I want you to work (same rules as file 01)

Think before coding: state assumptions, ask when uncertain, show me the options instead of silently picking
one, push back if there's a simpler way, stop and say so if something is unclear.
Simplicity first: minimum code that solves it, nothing speculative, no abstractions for single-use code.
Surgical: touch only what you must, match existing style, don't refactor what isn't broken, only clean up
orphans your own change created.
Goal-driven: give me a numbered plan where each step names how it gets verified, then loop until those
checks pass.

- Scope

Wire the domain from file 03 to the schema from file 02. Still no HTTP, that's the next file.

- Ports and adapters

Define the port interfaces in `application/` and implement them with JPA in `infrastructure/`. Split them
rather than having one fat repository: `CandidateReader`, `CandidateWriter`, `EventReader`. Partly that's ISP,
mostly it's because it means the read paths physically cannot write, and it leaves the door open to CQRS
without committing to it now.

The domain must not import these adapters. Keep the dependency pointing inward.

- The transition write path

This is the only thing that mutates pipeline state, and it all happens in one transaction:

1. Load the candidate with its version for optimistic locking.
2. Ask the domain whether the transition is legal. If not, the typed error propagates.
3. Append one `stage_event` row with the next `seq`.
4. Update `current_stage`, `current_stage_since` and `reached_mask` on the candidate.

If any part fails, none of it happens. Write a test that forces a failure at step 4 and asserts no event row
was left behind.

For `seq`, use whatever you and I agreed in file 03. If we didn't settle it, settle it now before writing code.

- Projection rebuild

The three denormalised columns can theoretically drift from the event log, so build a use case that rebuilds
`current_stage`, `current_stage_since` and `reached_mask` for a candidate from their events alone. Expose it
as a port now; the admin endpoint comes in the next file.

Write a test that seeds a candidate with several events, deliberately corrupts the projection columns with a
direct SQL update, runs the rebuild, and asserts the values match what the log implies. That test is how I
prove the log really is the source of truth rather than just claiming it in the README.

- Reads

A method returning the board: candidates grouped by stage with a count per stage. A method returning one
candidate's timeline ascending by seq. A method returning a page of candidates using keyset pagination off a
cursor, not OFFSET. At 200 rows OFFSET makes no difference, I want keyset because it's the kind of thing
nobody goes back and fixes later.

Time in current stage is computed from `current_stage_since` and the injected clock, not stored.

- Testing

Testcontainers with real Postgres for everything here, not H2. Most of what this relies on is
Postgres-specific and H2 would let broken things pass silently.

Connect as the restricted application role from file 02, not as the migration owner, so the tests exercise the
same permissions production would have.

- Don't build

No controllers, no DTOs, no HTTP. No caching. No search, the search query building comes in file 08 and it
will have its own approach.

- Done when

A transition writes exactly one event and updates all three projection columns atomically. The rollback test
proves no orphaned events. The rebuild test proves the projection can be reconstructed from the log. Keyset
pagination returns stable pages when rows are inserted between requests, and there's a test showing that.

- Carry-overs from file 03

Two things to deal with before the main work.

First, the gap you flagged: the initial APPLIED event has no home. It isn't a transition, there's no origin
stage, so nothing in the domain produces it. Give candidate creation its own entry point here, and treat it as
the one place `from_stage IS NULL` and `seq = 1` are written together. It needs the same transactional
guarantee as a transition: the candidate row and its first event are one atomic write or neither happens.
Write the test that proves a failed candidate creation leaves no event behind, and one that proves the V4
check constraint rejects a second event with `from_stage IS NULL`.

Second, `TransitionRule` is `sealed` and I want that dropped. You were half right that it's inert, but the
sharper problem is a contradiction: `TransitionRules` composes rules from a list and its comment says a new
kind of move is a new rule rather than an edit to an existing one, while `sealed` says no new rule can exist
outside that one file. Those can't both be the design. Keep the list-based composition, because deriving
`legalTargets` by filtering `Stage.values()` through `isLegal` is the good part and means the alternatives in
the error can never drift from the rules. Drop `sealed`, and give `TransitionRules` a public constructor
taking a rule list alongside `standard()`. That's a one-word change plus one constructor, not a rewrite.

While you're there: the README will eventually claim "adding a stage means an enum constant and a rule". That
claim is false as written, because a new stage also needs a Postgres enum value in a migration, a
`reached_mask` bit, and a board column in the frontend. Don't test that claim. Test the one that's true, that
a new kind of *move* needs no edit to any existing rule, and tell me what the honest version of the sentence
is so I can put it in the README rather than something I'd have to walk back.
