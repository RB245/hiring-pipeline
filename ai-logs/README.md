# AI chat logs

This project was built with AI assistance across two separate streams, kept apart here on
purpose because they did different jobs.

**The builder** received the ten prompts in `prompts/` one at a time and wrote the code.
Files `01-` through `10-` below are those exchanges.

**The reviewer** was a second model used to design the prompts, check each phase's claims
against the actual repository rather than against the builder's summary, and decide what to
accept, amend or reject. That thread is `00-review-thread.md`.

The split mattered. Several times the builder reported a phase as green and independent
verification found the check was structurally incapable of failing — an ArchUnit rule
matching zero classes, a fixed clock sitting on a whole second so truncation had nothing to
truncate, and metrics assertions running against an endpoint that returns 404 under test.
A single model marking its own homework would have missed all three.

## Contents

| File | Phase |
|---|---|
| `00-review-thread.md` | Prompt design, per-phase verification, decisions |
| `01-scaffold.md` | Repo layout, Docker Compose, Flyway baseline, ArchUnit boundary |
| `02-schema.md` | Tables, indexes, append-only events, split database roles |
| `03-domain.md` | Stage machine, transition rules |
| `04-persistence.md` | Ports, JPA adapters, atomic write path |
| `05-api.md` | HTTP endpoints, idempotency, RFC 9457 errors |
| `06-crosscutting.md` | Rate limiting, authenticated actor, observability, seed data |
| `07-search-parsing.md` | Normalizer, lexer, parser, validator |
| `08-search-execution.md` | Query building, ranking, zero-result relaxation |
| `09-frontend.md` | Board, search bar, candidate drawer |
| `10-docs.md` | README, ADRs, architecture PDF |

## Where I disagreed with the AI

See `07-search-parsing.md`. Summary is in the project README.
