- 03 — The domain model

- How I want you to work (same rules as file 01)

Think before coding: state assumptions, ask when uncertain, show me the options instead of silently picking
one, push back if there's a simpler way, stop and say so if something is unclear.
Simplicity first: minimum code that solves it, nothing speculative, no abstractions for single-use code.
Surgical: touch only what you must, match existing style, don't refactor what isn't broken, only clean up
orphans your own change created.
Goal-driven: give me a numbered plan where each step names how it gets verified, then loop until those
checks pass.

- Scope

Everything in this file lives in `domain/` and has zero Spring imports, zero JPA imports, and no knowledge
that a database exists. The ArchUnit test from file 01 should keep passing the whole way through. Work
test-first here: write the failing test, then make it pass.

- The rules

Stages are APPLIED, SCREENING, INTERVIEW, OFFER, HIRED, plus REJECTED reachable from any non-terminal stage.
HIRED and REJECTED are terminal.

Four things must hold, and they must hold here in the domain layer rather than in a controller or only in the
database:

- Advancing moves exactly one stage forward. Applied straight to Interview is rejected.
- No going backwards. Interview back to Screening is rejected.
- Nothing moves out of a terminal stage.
- Every accepted transition produces exactly one event.

- How to model it

A `Stage` enum that knows its own successor and whether it's terminal.

Transitions as a sealed interface, something like `TransitionRule` permitting `AdvanceRule` and `RejectRule`,
resolved through a registry. The test I'm going to apply in file 10 is that adding a new stage should mean
adding an enum constant and a rule and touching nothing else, so build it that way now rather than planning to
retrofit it.

A rejected transition throws a typed error carrying the from stage, the to stage, and the list of stages that
*would* have been legal. Both the API and the UI need that last part to explain themselves to the recruiter,
so a bare "invalid transition" message isn't enough.

An accepted transition returns a value object describing what should be persisted: the new stage, the event to
append, and the updated reached-mask. The domain decides, the persistence layer writes. Don't let the domain
know how it gets stored.

Inject `java.time.Clock` anywhere you read the time, using the bean from file 01. Never call `Instant.now()`
directly. Time-in-stage logic runs through everything here and I need all of it deterministic under test.

- One thing to think about before you build

Where does `seq` come from? The domain shouldn't be querying the database to find out what number the next
event is. Tell me how you'd handle that before you implement it, and if you think the cleanest answer is that
`seq` belongs to the persistence layer rather than the domain, say so.

- Don't build

No JPA entities, no repositories, no controllers, no persistence of any kind. That's the next file.

- Done when

There's a table-driven test covering all 36 stage pairs, asserting allowed or rejected for each. Exhaustive,
not a sample, because the interesting failures are the ones nobody thinks to test.

There's a test proving the typed error carries the legal alternatives.

There's a test using a fixed clock proving time-in-stage is computed correctly across a day boundary.

ArchUnit still passes, meaning `domain` has picked up no framework dependencies.

Give me your plan first, tell me if anything above seems overcomplicated, then build it and stop.
