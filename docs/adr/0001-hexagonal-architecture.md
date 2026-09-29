# ADR 0001 — Hexagonal architecture

Status: accepted

## Context

The interesting part of this system is a set of rules: which stage moves are legal, that
history is append-only, that a candidate's position and their event log cannot disagree.
Those rules are the thing worth getting right and the thing most likely to be asked about
in review.

The default Spring layering — controller calls service calls repository — puts those rules
in a class that also holds a `JpaRepository`, a `Clock` injected by Spring, and often an
HTTP concern or two. That arrangement works, but it makes the rules hard to read in
isolation and impossible to test without a framework. It also makes "can we swap the
search backend" an unanswerable question, because nothing marks where the boundary is.

## Decision

Four packages, with dependencies pointing inward.

- `domain` — the stage machine, the transition rules, the immutable decision objects. No
  Spring, no JPA, no HTTP. Collaborators are assembled in `infrastructure.DomainConfig`
  because the domain has no annotations of its own.
- `application` — use cases and the ports they need: `CandidateReader`, `CandidateWriter`,
  `CandidateSearch`, `EventReader`, `JobReader`.
- `infrastructure` — the adapters that implement those ports, and where JPA lives.
- `api` — controllers, filters, the Problem Details envelope.

`search` sits beside these as its own module: the query language is a self-contained
concern that the application layer consumes.

Enforced by ArchUnit rather than by convention. Four rules fail the build if the domain
touches Spring, touches JPA, or reaches outward, or if a use case reaches for an adapter.

## Consequences

The domain is testable with plain JUnit and no context, which is why it sits at 100% line
coverage without anybody working at it.

Swapping an adapter is a real option rather than a claim. `CandidateSearch` is the port
behind the whole search feature; an OpenSearch implementation is a new class in
`infrastructure` and no change to the domain, the use cases or the parser. That is the
specific thing this layering buys, and it is the honest answer to "what if Postgres
trigram is not enough".

The cost is indirection: a read goes controller → use case → port → adapter, and there are
more files than a three-layer version would have. At this size that is a real cost and it
is accepted deliberately.

It also forces questions rather than letting them slide. `RecruiterProperties` started in
`api` and moved to `application` because the seeder needed the recruiter's identity and
the layering forbids `application` reaching into `api`. The ArchUnit rule turned a
convenience into a design decision, which is the point of having it.
