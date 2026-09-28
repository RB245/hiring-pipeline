# 00 — Review thread

A second model was used to design the ten prompts, then to verify each phase against the
repository rather than against the builder's own summary. Every phase below was checked by
reading the committed files and re-running the suite, not by reading the report.

---

## Before any code

Chose Spring Boot over a Next.js full-stack backend. The domain is rules-heavy — a state
machine, transactional invariants, an append-only ledger — and database-level enforcement
(triggers, role grants) is the strongest available proof of immutability. A single Next.js
deployable would have shipped faster and demonstrated less.

Decided against an LLM in the search query path before writing the prompts. Reasoning in
`07-search-parsing.md`.

---

## Phase 1 — Scaffold

Builder reported eight checks green. Three were actually unverified: Docker wasn't installed
on the machine, so Compose and the Flyway baseline had never run, and CI had never executed.
It marked these ⚠️ rather than green, which was the correct call.

Two problems found by independent inspection, neither in the report:

- `gradlew` was committed as mode `100644`. CI runs `./gradlew` on `ubuntu-latest`, where a
  non-executable wrapper fails with `Permission denied`. `core.filemode` is false on Windows,
  so git never recorded the bit.
- Git was set to convert `gradlew` to CRLF on checkout, which breaks the Linux build inside
  the image with `bad interpreter: /bin/sh^M`. Fixed with `.gitattributes`.

Both are the same underlying problem — authoring on Windows, building on Linux — caught twice
in one phase.

---

## Phase 2 — Schema

Accepted. The builder disagreed with the specified index set and was right: a btree on
`reached_mask` is never chosen by the planner because `&` isn't a searchable operator. It
measured both paths (8.4ms masked predicate against 11.3ms for the equivalent EXISTS pair),
kept the column, dropped the index, and wrote the numbers into the migration.

It also found that the motivating search example doesn't work at default settings —
`'sharam' % 'Priya Sharma'` scores 0.25 against a 0.3 threshold — and identified
`word_similarity` with the `%>` operator as the fix. That finding was carried forward into
the prompt for phase 8 rather than left to be rediscovered.

Verified live rather than on report: `pipeline_app` holds `INSERT, SELECT` on `stage_event`
and nothing else, all three tables are owned by `pipeline_migrator`, and the application's
ten pooled connections are genuinely `pipeline_app`.

Decisions made at this point:

- Keep the `BEFORE TRUNCATE` trigger the builder added beyond spec. Row-level triggers do not
  fire on TRUNCATE, so without it the guarantee has a hole.
- Leave `pipeline_app` with SELECT only on `job`. An app managing one opening should not be
  able to create openings; the seeder was moved to the migrator identity instead.

---

## Phase 3 — Domain

Accepted, with one design change.

The builder found a latent bug in the phase 1 ArchUnit rules: `..api..` matches any package
with an `api` segment, including `org.assertj.core.api`, so the rules reported 68 false
violations the moment the domain gained tests.

It then observed that its own `sealed` interface was inert. The sharper problem was a
contradiction it hadn't named: `TransitionRules` composes rules from a list and documents
that a new move is a new rule, while `sealed` forbids any rule existing outside that one
file. Both could not be the design. Decision: drop `sealed`, keep the list-based composition,
because deriving `legalTargets` by filtering `Stage.values()` through `isLegal` means the
alternatives shown to the recruiter can never drift from the rules that produced them.

Also killed a claim before it reached the README. "Adding a stage means an enum constant and
a rule" is false — a stage also needs a Postgres enum value, a `reached_mask` bit, and a board
column. The builder supplied the honest version, and the distinction it drew is worth keeping:
the first sentence is a property and can be tested; the second is a checklist and cannot.

---

## Phase 4 — Persistence

Accepted. The `saveAndFlush` detail is the one worth noting: flushing the candidate before
the event is what makes the no-orphaned-event assertion mean anything, because the row is
genuinely in the database and only a rollback can remove it. Without the flush the test
would pass because nothing had been written yet.

Verification note: an initial `./gradlew build` returned every task `UP-TO-DATE` and proved
nothing. A `clean build` was needed to actually execute the suite.

---

## Phase 5 — API

Accepted. The significant finding was a bug the test suite could not have caught.

Postgres stores timestamps at microsecond resolution and rounds. A nanosecond `Instant` read
back is not even a prefix of the one written, so two supposedly identical idempotent responses
differed in production while the test passed. The test clock sat on a whole second, so
truncation never had anything to truncate — the test was structurally incapable of failing.

Fixed by stamping events at microsecond resolution in the domain, which holds for every clock
including the ones tests inject.

One reframing: the builder listed "concurrent requests sharing an idempotency key get 409
rather than the original response" as an open gap. It isn't a gap. The original response does
not exist yet, so there is nothing to replay, and returning 409 is what Stripe does in the
same situation. Moved from open to decided; the outer retry was not built.

---

## Phase 6 — Cross-cutting and seed

Accepted, and the builder overrode the specification correctly.

The prompt asked for a fixed seed timestamp. It used today-truncated-to-the-day with fixed
offsets instead, because a hard-coded base makes "moved to Interview in the last three days"
false the day after it is chosen. Fixed offsets and fixed name ordering still give an
identical shape on every run.

It also declined to add a request latency timer on the grounds that
`http_server_requests_seconds` had been published since phase 1 and a second would only be a
number to reconcile. Correct.

Third instance of a check that could not fail: Boot disables metrics export under test, so
`/actuator/prometheus` returned 404 and every metric assertion was asserting against an empty
response.

`RecruiterProperties` moved from `api` to `application` because the seeder needs the
recruiter's identity and the layering forbids `application` reaching into `api`. The ArchUnit
rule forced a real architectural question and the answer changed the design.
