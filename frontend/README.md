# Frontend

Next.js 15 App Router, TypeScript, Tailwind, shadcn/ui, TanStack Query, Zod.

```
make up                  # the whole stack, seeded, on http://localhost:3000
cd frontend && npm test  # the search bar
```

## One screen

The search box sits above either the board or the results — never both, because a board that
is not filtered by the query above it is a board that contradicts it.

The query lives in the URL (`/?q=stage:offer`). That is the same argument the backend made
for spelling the looser name match as a field rather than a request flag: whatever produced
these results should be visible in, and reproducible from, the thing you can copy. It is
written with `history.replaceState` rather than a router push, so a settled keystroke does
not re-run the server render of a board that has not changed.

## The BFF

The browser never talks to Spring. Every request goes to `/api/…`, which is a single Route
Handler that attaches the API key and spends a per-session budget on the way.

- **The key** lives only in `lib/api.ts`, which imports `server-only`. That turns "do not
  import this from a client component" from a convention into a build error, which matters
  because it is the one mistake here that would be both silent and unrecoverable.
- **The allowlist** is explicit. `POST /candidates` (create a person) and
  `POST /admin/rebuild-projections` (rewrite every projection) are real endpoints with real
  authority that this UI does not use, so they are not reachable through it. A new backend
  endpoint is closed until somebody opens it.
- **Errors pass through unchanged.** A 422 from the search parser carries the span the input
  underlines and the corrections it offers as chips; re-shaping it here would mean a second
  copy of an error contract that is already precise.

### The session limit is in process, and that is a real limit

`SESSION_RATE_LIMIT` (240/minute) is kept in a `Map` in the Node process. It resets on
restart and is **not shared between replicas** — a second frontend container would give each
session two budgets.

It is not a security boundary: the backend already limits by API key, and that is the limit
protecting the database. This one protects the *other* sessions, because every browser here
shares one key — without it a single tab stuck in a render loop would spend the whole key's
60-per-minute search budget and everyone else would see 429s they did nothing to cause.
Splitting it per session turns that into one broken tab. Scaling past one container means
moving it to the Redis the backend already uses for the same job.

## What is tested, and what is not

`src/components/search-bar.test.tsx` — ten tests, and the only component tests here. The
search bar is the one piece with logic rather than layout: it decides which characters to
underline, and getting that wrong points a recruiter at the wrong part of her own sentence.

The tests mock the API **including its spans**, because what is under test is that the
component uses the offsets it was given rather than working them out again. It cannot work
them out: the normaliser rewrites "for more than a week" into `in_stage_for:>7d` before
parsing it, so the characters a predicate corresponds to are not derivable from the text on
screen. The server knows; the client draws.

Everything else renders what it is handed, and a test asserting that a badge contains the
string it was passed would only restate the component.
