- 05 — The HTTP API

- How I want you to work (same rules as file 01)

Think before coding: state assumptions, ask when uncertain, show me the options instead of silently picking
one, push back if there's a simpler way, stop and say so if something is unclear.
Simplicity first: minimum code that solves it, nothing speculative, no abstractions for single-use code.
Surgical: touch only what you must, match existing style, don't refactor what isn't broken, only clean up
orphans your own change created.
Goal-driven: give me a numbered plan where each step names how it gets verified, then loop until those
checks pass.

- Endpoints

 
POST   /api/v1/candidates
GET    /api/v1/candidates?cursor=&limit=
GET    /api/v1/candidates/{id}
GET    /api/v1/candidates/{id}/events
POST   /api/v1/candidates/{id}/transitions
GET    /api/v1/pipeline
POST   /api/v1/admin/rebuild-projections
 

`/pipeline` returns the board: candidates grouped by stage with a count per column. `/events` returns the
timeline ascending by seq. `/candidates` pages by cursor, never OFFSET.

Controllers stay thin. They map DTOs, call an application use case, and map the result back. No business
logic, no repository calls, no transaction management in the controller.

- The transition endpoint

This is the only endpoint that mutates pipeline state.

 http
POST /api/v1/candidates/{id}/transitions
Idempotency-Key: <uuid>

{ "expectedCurrentStage": "SCREENING", "toStage": "INTERVIEW", "reason": "Strong screen" }
 

`expectedCurrentStage` is optimistic concurrency. If the recruiter has a stale board open, she gets a 409
rather than silently double-advancing someone. `Idempotency-Key` is backed by the unique index from file 02,
so a retry or a double-click returns the original result instead of writing a second event.

Both of those need proper tests, not happy-path smoke tests. Specifically: replay the same idempotency key
twice and assert exactly one event exists and both responses match; and fire two concurrent transitions at the
same candidate and assert exactly one succeeds while the other gets a 409.

- Responses and errors

The candidate response includes `timeInCurrentStage` both as an ISO-8601 duration and humanised ("6 days"),
computed from the injected clock.

All errors are RFC 9457 Problem Details with a stable `type` URI, the request's correlation ID, and for an
invalid transition, the list of stages that would have been legal. The recruiter's UI needs to be able to say
"you can't move Priya from Hired" and mean it specifically.

Map the domain's typed errors to status codes in one `@RestControllerAdvice`. Don't scatter try/catch through
the controllers. An illegal transition is a 422, a missing candidate is a 404, a stale
`expectedCurrentStage` is a 409, a malformed body is a 400.

- OpenAPI

Expose OpenAPI and Swagger UI. Document the idempotency header and the 409 semantics, since those are the two
things a client integrating with this would get wrong.

- Don't build

No auth yet, no rate limiting, no search parameter on `/candidates`. Those are files 06 through 08. If you
find yourself wanting to add a `q=` parameter now, don't, it'll be designed properly later.

- Done when

Every endpoint works end to end against Testcontainers Postgres. The idempotency replay test and the
concurrent transition test both pass. An illegal transition returns 422 with the legal alternatives in the
body. Swagger UI renders the full API. And a MockMvc test asserts that the Problem Details shape is consistent
across at least three different error types, because inconsistent error shapes are the thing clients actually
suffer from.
