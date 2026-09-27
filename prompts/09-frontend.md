- 09 — Frontend

- How I want you to work (same rules as file 01)

Think before coding: state assumptions, ask when uncertain, show me the options instead of silently picking
one, push back if there's a simpler way, stop and say so if something is unclear.
Simplicity first: minimum code that solves it, nothing speculative, no abstractions for single-use code.
Surgical: touch only what you must, match existing style, don't refactor what isn't broken, only clean up
orphans your own change created.
Goal-driven: give me a numbered plan where each step names how it gets verified, then loop until those
checks pass.

- Stack

Next.js 15 App Router, TypeScript, Tailwind, shadcn/ui, TanStack Query, Zod at every boundary.

Use Route Handlers as a BFF. The browser never talks to Spring Boot directly; the Route Handler holds the API
key from file 06 and adds a per-session limit on top. Initial board load through React Server Components.

Three screens' worth of work, and no more. Resist adding settings pages, dark mode toggles, or anything else
I didn't ask for.

- The board

Six columns, one per stage, with a live count on each. Advance and Reject buttons that update optimistically
and roll back on a 409 from file 05.

On terminal candidates those buttons are disabled with a tooltip saying why. The UI should teach the state
machine rather than silently enforcing it, because a recruiter who doesn't know why a button is greyed out
will assume the app is broken.

- The search bar

This is the part I want to look good, since it's where most of the work went.

- Token highlighting in the input, with the invalid span underlined in red using the span the API returned.
  Use the span, don't re-derive the position on the client.
- The error message inline underneath, with the "did you mean" options as clickable chips that rewrite the
  query in place.
- Interpretation chips below the box showing how her sentence parsed, so `Stage = Interview` /
  `In stage > 7 days` / `Not rejected`. She types a sentence, she should be able to see she was understood
  before she trusts the results. Drive these from `/search/explain`.
- Autocomplete from `/search/suggest`.
- A few example query pills so a first-time user knows what's possible at all.
- On zero results, render the relaxation suggestions from file 08 as one-click alternatives.
- Debounce at 250ms. Show `score` and `matchedOn` on hover.

- The candidate drawer

A vertical timeline of every event in order. A prominent badge for how long they've been in the current stage.
Relative timestamps with the absolute one on hover. A small "append-only, this history can't be edited" badge
on the timeline.

- Accessibility

The board should be keyboard navigable. The result count needs `aria-live` so a screen reader announces it
changing. The drawer needs a focus trap that restores focus to the trigger on close.

- Testing

Vitest and React Testing Library, focused on the search bar: that it renders the error message, underlines the
correct span, and that clicking a suggestion chip rewrites the query. I don't need broad component coverage, I
need that one component proven, because it's the piece with real logic in it.

- Done when

`docker compose up` gives a working app with the seeded data in it. All eight acceptance queries from file 07
work end to end from the UI. A deliberately broken query shows a red underline in the right place with a
working suggestion chip. Advancing a candidate updates the board without a reload, and advancing a stale one
rolls back visibly rather than failing silently.
