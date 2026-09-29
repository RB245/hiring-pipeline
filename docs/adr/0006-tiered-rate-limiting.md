# ADR 0006 — Rate limits tiered by cost

Status: accepted

## Context

The API needs limiting. One global number per key is the usual answer and it forces a bad
choice: set it low enough to protect the expensive endpoint and an ordinary board refresh
starts failing; set it high enough for comfortable browsing and a search loop can hammer
the database unopposed.

The endpoints differ by more than an order of magnitude in what they cost. A board read is
one indexed query. A search parses a query, runs a trigram scan, and scores every match
before sorting. A transition writes two rows in a transaction and takes a lock.

## Decision

Three buckets, chosen by what a request does rather than by where it is mapped.

| Tier | Default | Covers |
|---|---|---|
| `WRITE` | 20/min | Anything that mutates pipeline state |
| `READ` | 300/min | Board, list, detail, timeline |
| `SEARCH` | 60/min | `/api/v1/search/*`, and the candidates list with a `q=` |

The last row is the part worth stating. A search reaches the API two ways, and tiering
only the tidy-looking one would leave the expensive path sitting on the 300/min read
bucket. The filter inspects the request, not the route.

Backed by bucket4j — Caffeine in process by default, Lettuce against Redis when
configured — behind a `RateLimiterPort`, so which one is a configuration choice rather
than a code change. Limit headers go out on every response, not only on a 429: a client
that only learns the limit by breaching it has no way to back off before breaching it.

The BFF adds a second, per-session budget on top. That one is not protecting the database
— the backend's key-level limit already does. It protects the *other* sessions, because
every browser shares one API key: without it, a single tab in a render loop spends the
whole key's search budget and everyone else sees 429s they did nothing to cause.

## Consequences

Exhausting the write budget does not stop her looking at the board, which
`RateLimitApiTest` asserts directly rather than leaving to inspection.

A 429 comes back as Problem Details with `Retry-After`, in the same envelope as every
other error. The limiter runs in a servlet filter, outside any `@RestControllerAdvice`, so
this took extracting the error shaping into a component both can call — an error envelope
that only applies once a request reaches a controller is not a contract.

The per-session budget is in process, so it resets on restart and is not shared between
replicas. That is honest for one container and one recruiter, and it is written down in
`frontend/README.md` as a tripwire rather than left for someone to discover after scaling
out.

Getting the numbers right turns out to be load-bearing in a way that was not anticipated.
The 60/min search tier caught a real frontend bug: autocomplete was keyed on the raw input
rather than the debounced value, so it fired once per keystroke, and a fifty-character
sentence exhausted the budget before it was finished typing. The limit surfaced a client
defect that no test had, because every test typed into a mock that never complained.
