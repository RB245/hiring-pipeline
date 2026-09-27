- 01 — Working agreement and scaffold

- How I want you to work

These rules hold for everything that follows. I'll remind you of them each time in short form.

 Think before coding.  Don't assume, don't hide confusion, surface tradeoffs. State your assumptions
explicitly and ask if you're uncertain. If there are multiple reasonable interpretations, show me them rather
than silently picking one. If a simpler approach exists, say so and push back. If something is unclear, stop,
name exactly what's confusing, and ask.

 Simplicity first.  The minimum code that solves the problem, nothing speculative. No features beyond what I
asked for. No abstractions for single-use code. No flexibility or configurability I didn't request. No error
handling for impossible scenarios. If you write 200 lines and it could be 50, rewrite it. Ask yourself whether
a senior engineer would call it overcomplicated, and if the answer is yes, simplify.

 Surgical changes.  Touch only what you must and clean up only your own mess. Don't improve adjacent code,
comments or formatting. Don't refactor things that aren't broken. Match the existing style even where you'd do
it differently. If you notice unrelated dead code, mention it, don't delete it. Do remove imports, variables
and functions that your own change orphaned. Every changed line should trace directly to something I asked for.

 Goal-driven.  Turn the task into verifiable goals before you start. Give me a short numbered plan where each
step names how it gets checked, like "add the transition endpoint → verify: POST returns 201 and one event
row exists". Then loop until those checks actually pass. Don't hand me something and ask whether it works.

- The project

A recruiter manages candidates for a single job opening. A candidate moves Applied > Screening > Interview >
Offer > Hired, one stage at a time, and can be rejected at any point before being hired. She needs a board
showing everyone grouped by stage, the ability to move people forward, and a view of any candidate's complete
history including how long they've been in the current stage. That history is an audit trail: once recorded,
it can never be altered.

There's also a single search box that has to handle questions like "who has been stuck in Screening for more
than a week" and "who reached Offer but didn't get hired". That's a large piece of work and it comes later, in
files 07 and 08. Ignore it for now.

This is a take-home reviewed by senior engineers. They care about judgement, not feature count. I have about a
day and a half, so I'd rather build a small number of things properly than many things halfway.

- Stack

Java 21, Spring Boot 3.4, Postgres 16, Redis 7, Next.js 15 with the App Router and TypeScript. Flyway for all
schema, with ddl-auto no higher than `validate`. Gradle with the Kotlin DSL.

- What to build in this file

Just the skeleton. Nothing clever.

1. Repo layout: `/backend`, `/frontend`, `/docs`, `/ai-logs`, plus `docker-compose.yml`, `Makefile`,
   `.env.example` and a `.gitignore` at the root.
2. Backend package layout under `com.pipeline`: `domain/` (pure Java, zero Spring imports),
   `application/` (use cases and the port interfaces they depend on), `infrastructure/` (JPA, adapters,
   clock), `api/` (controllers, DTOs, exception handling).
3. An ArchUnit test that fails if anything in `domain` imports `org.springframework`,
   `jakarta.persistence`, or anything from `infrastructure` or `api`. I want that boundary enforced by a
   test rather than by me remembering it.
4. `docker-compose.yml` bringing up Postgres and Redis with healthchecks, and the backend depending on them.
   `docker compose up` should be the only command needed to run this, and I want that true from day one
   rather than bolted on at the end.
5. Flyway wired up with an empty baseline migration. Real schema comes in the next file.
6. Spring Actuator exposing health and Prometheus.
7. A `Clock` bean returning `Clock.systemUTC()`, and a test configuration providing a fixed clock. A lot of
   "how long has this person been sitting here" logic is coming and I need all of it deterministic.
8. A GitHub Actions workflow that builds and runs tests. It can be thin for now.

- Don't build

No entities, no tables, no endpoints, no business logic, no frontend beyond `create-next-app` defaults.

- Done when

`docker compose up` starts Postgres, Redis and the backend; `/actuator/health` returns UP; Flyway reports a
successful baseline; `./gradlew test` passes and includes the ArchUnit test; CI is green.

Give me your plan first, flag anything you'd do differently, then build it and stop.
