- 06 — Rate limiting, auth, observability, seed data

- How I want you to work (same rules as file 01)

Think before coding: state assumptions, ask when uncertain, show me the options instead of silently picking
one, push back if there's a simpler way, stop and say so if something is unclear.
Simplicity first: minimum code that solves it, nothing speculative, no abstractions for single-use code.
Surgical: touch only what you must, match existing style, don't refactor what isn't broken, only clean up
orphans your own change created.
Goal-driven: give me a numbered plan where each step names how it gets verified, then loop until those
checks pass.

- Rate limiting

Bucket4j backed by Redis, sitting behind a `RateLimiterPort` so there's an in-memory Caffeine adapter for
running locally without Redis.

Tier it by cost rather than one global number: writes 20/min, reads 300/min, and a search bucket at 60/min
that nothing uses yet but files 07 and 08 will. Key on API key, falling back to IP.

Return 429 with `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` and `Retry-After`, and a
Problem Details body matching the shape from file 05.

Implement it as a cross-cutting filter or interceptor. It should not be visible in any controller.

- Auth, deliberately minimal

A seeded recruiter with a RECRUITER role, resolved by a Spring Security filter from a static API key in
config. I'm not building a login flow for a take-home and I don't want you to.

But every `stage_event` must carry a real `actor_id` and `actor_name` taken from that principal, not a
hardcoded constant deep in a service. An audit trail that can't tell you who did something is half useless,
and that's the part I actually care about here.

Write a test asserting that a transition made by the authenticated recruiter lands their id and name on the
event row.

- Observability

Structured JSON logs. A correlation ID in MDC on every request and every log line, returned in a response
header, and included in Problem Details bodies. Email addresses masked in logs.

Micrometer to Prometheus through the actuator endpoint already exposed in file 01. Add counters for
transitions by type and rate limit rejections, and a timer for request latency. Don't add metrics
speculatively, those three are the ones I'd actually look at.

All config through environment variables, `.env.example` updated, no secrets in the repo.

- Seed data, and take this seriously

It sounds like a chore but it's load-bearing. Without backdated event histories, half the search features
built in the next two files will return nothing and the whole thing will look broken in a demo.

Around 200 candidates with realistic histories spread across the stages, with events genuinely backdated
rather than all written at seed time. Guarantee at least: 8 people stuck in Screening for over a week, 5 who
moved to Interview within the last three days, 6 who reached Offer and were then rejected, 3 hired, and a
candidate named Priya Sharma.

Seed deterministically from a fixed base timestamp so it's identical on every run and I can screenshot it.
Make it idempotent so restarting the container doesn't duplicate everyone.

- Done when

A burst of requests past the write limit returns 429 with correct headers, and a test proves it. The actor on
a new event matches the authenticated principal. Logs come out as JSON with a correlation ID that matches the
response header. `docker compose up` on an empty volume produces a populated database, and a query for
candidates in Screening for more than 7 days returns at least 8 rows.

- One thing before you stop

The next two files are the search feature. It's a single box, and the recruiter should be able to type things
like "who has been stuck in Screening for more than a week" or "everyone except rejected candidates", combine
those conditions, get the best matches first, and be told why a query is wrong instead of getting an empty
list.

Before I send you my spec for it, tell me in a short paragraph how you would build that and how you would
handle invalid input. I want your take before you see mine. Then stop and wait for me.

- Carry-over from file 02

`pipeline_app` has SELECT only on the `job` table, and that's deliberate: an app managing one opening has no
business creating openings. So the seeder must not create the job through the application role. Insert it in
a migration, or run the seeder under the migrator identity. Don't "fix" this by granting INSERT on `job` to
`pipeline_app`.

Candidates and events are different. The app role does hold INSERT on both, so seeding those through the
application path is fine, and is arguably a better test of that path than raw SQL would be.
