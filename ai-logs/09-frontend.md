# 09 - Frontend: board, search bar, candidate drawer

Prompt given: `prompts/09-*.md`

---

<!-- Let me see what's already in frontend/ before planning anything.

  Searched for 2 patterns, ran 5 shell commands

Bare create-next-app scaffold — no shadcn, TanStack Query, Zod or Vitest yet. npm registry is reachable, backend has a Dockerfile, compose has no frontend service. Two decisions I'd rather not make silently.

One I'm deciding myself and reporting: ?q= lives in the URL and drives the results, synced shallowly so it doesn't re-run the RSC board fetch. That's the same argument you made for name_like: being a field rather than a flag — the box should describe its own results, and a shared or refreshed link should reproduce them.

The BFF: one catch-all Route Handler with an explicit method+path allowlist, rather than six near-identical handlers. It holds the API key and applies a per-session token bucket keyed on an httpOnly cookie it sets on first request. The bucket is in-process, so it resets on restart and wouldn't be shared across replicas — fine for one recruiter, and I'll say so in the README rather than pretend otherwise. Zod validates in the typed fetchers, which both the RSC path and the browser path go through, so every response is parsed exactly once wherever it entered.

┌─────┬────────────────────────────────────────────────────┬───────────────────────────────────────────────────┐
│  #  │                        Step                        │                    Verified by                    │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 1   │ Deps + config: TanStack Query, Zod, shadcn/ui,     │ npm run build and npm test both run               │
│     │ Vitest, RTL, output: 'standalone'                  │                                                   │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 2   │ Zod schemas for all seven response shapes +        │ Schemas parse real responses captured from the    │
│     │ Problem Details                                    │ running backend                                   │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 3   │ BFF: catch-all handler, allowlist, API key,        │ Browser never sees the key; over-limit returns    │
│     │ per-session bucket                                 │ 429                                               │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 4   │ RSC board + client BoardView, six columns with     │ Board renders seeded data through compose         │
│     │ live counts                                        │                                                   │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 5   │ Optimistic advance/reject, rollback on 409         │ Stale transition visibly rolls back               │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 6   │ Terminal buttons disabled with a tooltip that says │ Depends on Q1 below                               │
│     │  why                                               │                                                   │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 7   │ Search bar: highlighting, error span, chips,       │ Vitest: error message, correct span, chip rewrite │
│     │ autocomplete, pills, relaxations                   │                                                   │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 8   │ Drawer: timeline, time-in-stage badge,             │ Focus trap restores to trigger                    │
│     │ relative/absolute times, append-only badge         │                                                   │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 9   │ Accessibility: keyboard board, aria-live count,    │ Keyboard-only walkthrough                         │
│     │ focus restore                                      │                                                   │
├─────┼────────────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 10  │ frontend service + Dockerfile + backend            │ docker compose up serves a working app; eight     │
│     │ healthcheck; CI job                                │ acceptance queries pass from the UI               │
└─────┴────────────────────────────────────────────────────┴───────────────────────────────────────────────────┘

● User answered Claude's questions:
· The board needs to disable Advance/Reject on terminal candidates and explain why. Where should it learn that from? → Add legalTargets to CandidateResponse (Recommended)
· How should the board and the search results share the screen? → One route; results replace the board (Recommended)

Both accepted. Starting with the backend field, since the board depends on it.

● Update(backend\src\main\java\com\pipeline\api\CandidateResponse.java)
Added 8 lines
import com.pipeline.application.CandidateSummary;
import com.pipeline.application.SearchHit;
import com.pipeline.domain.Stage;
import com.pipeline.domain.TransitionRules;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;

 * list, rather than null or zero, because a candidate has no relevance outside a question.
 * Omitted per property rather than by annotating the record, which would also hide a null
 * phone number and change what the list has always returned.
 *
 * <p>{@code legalTargets} is here so that a client can render the moves a candidate has
 * without knowing the rules that produced them. It is the same list the 422 body carries
 * when an illegal move is attempted, from the same {@link TransitionRules}, so a button the
 * board offers and a move the API accepts cannot disagree. Without it the board would have
 * to keep its own copy of the state machine, and the point of deriving legal moves by
 * filtering the stages through the rules was that there is only ever one copy.
 */
public record CandidateResponse(
        UUID id,

● Update(backend\src\main\java\com\pipeline\api\CandidateResponse.java)
Added 4 lines
        @Schema(description = "ISO-8601 duration", example = "PT144H") String timeInCurrentStage,
        @Schema(example = "6 days") String timeInCurrentStageHumanised,
        Instant createdAt,
        @Schema(
                        description = "The moves this candidate has from here. Empty means terminal.
",
                        example = "[\"HIRED\", \"REJECTED\"]")
                List<Stage> legalTargets,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(description = "Search only. 0 to 1, best first.", example = "0.86")
                Double score,

● Update(backend\src\main\java\com\pipeline\api\CandidateResponse.java)
Added 6 lines, removed 5 lines
                        example = "[\"name ~ 'sharam' (0.80)\", \"stage = Interview\"]")
                List<String> matchedOn) {

    static CandidateResponse of(CandidateSummary summary, Clock clock) {
        return of(summary, clock, null, null);
    static CandidateResponse of(CandidateSummary summary, Clock clock, TransitionRules rules) {
        return of(summary, clock, rules, null, null);
    }

    static CandidateResponse of(SearchHit hit, Clock clock) {
        return of(hit.candidate(), clock, hit.score(), hit.matchedOn());
    static CandidateResponse of(SearchHit hit, Clock clock, TransitionRules rules) {
        return of(hit.candidate(), clock, rules, hit.score(), hit.matchedOn());
    }

    private static CandidateResponse of(
            CandidateSummary summary, Clock clock, Double score, List<String> matchedOn) {
            CandidateSummary summary, Clock clock, TransitionRules rules, Double score, List<String>
 matchedOn) {
        Duration inStage = summary.timeInCurrentStage(clock);
        return new CandidateResponse(
                summary.id(),

                inStage.toString(),
                DurationFormat.humanise(inStage),
                summary.createdAt(),
                rules.legalTargets(summary.currentStage()),
                score,
                matchedOn);
    }

  Ran 3 shell commands

● Update(backend\src\test\java\com\pipeline\api\CandidateApiTest.java)
Added 42 lines
                .andExpect(jsonPath("$.timeInCurrentStageHumanised").value("6 days"));
    }

    /**
     * The board renders a button per legal target, so this list is the only thing standing
     * between it and a copy of the state machine in TypeScript. Asserted here rather than
     * trusted, because the failure mode is a button that offers a move the API refuses.
     */
    @Test
    void everyCandidateCarriesTheMovesItActuallyHas() throws Exception {
        UUID id = createCandidate("Knows Its Moves");

        mvc.perform(get("/api/v1/candidates/{id}", id))
                .andExpect(jsonPath("$.currentStage").value("APPLIED"))
                .andExpect(jsonPath("$.legalTargets").value(hasItem("SCREENING")))
                .andExpect(jsonPath("$.legalTargets").value(hasItem("REJECTED")))
                .andExpect(jsonPath("$.legalTargets.length()").value(2));
    }

    /** Empty rather than absent, so "terminal" is something the board can see and explain. */
    @Test
    void aTerminalCandidateCarriesNoMovesAtAll() throws Exception {
        UUID id = createCandidate("Out Of Moves");
        mvc.perform(post("/api/v1/candidates/{id}/transitions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedCurrentStage": "APPLIED", "toStage": "REJECTED"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/candidates/{id}", id))
                .andExpect(jsonPath("$.currentStage").value("REJECTED"))
                .andExpect(jsonPath("$.legalTargets.length()").value(0));
    }

    /** And the board carries them too, since that is where the buttons are. */
    @Test
    void theBoardCarriesThemOnEveryCard() throws Exception {
        createCandidate("On The Board With Moves");

        mvc.perform(get("/api/v1/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].candidates[0].legalTargets").exists());
    }

    @Test
    void oneDayIsSingular() throws Exception {
        UUID id = createCandidate("One Day");
        clock.advance(Duration.ofDays(1).plusHours(3));

  Ran 1 shell command

Backend contract is in place. Now the frontend dependencies.

  Ran 3 shell commands

@types/node@^20 from the scaffold conflicts with vitest 5, and Node here is v22 — so the scaffold's pin is wrong anyway. Bumping it rather than forcing the install.

  Ran 3 shell commands

Now I want the real API running to develop and verify against. Starting Postgres and the backend in the background.

  Ran 2 shell commands

While that boots, writing the schemas — the boundary everything else depends on.

● Write(frontend\src\lib\schemas.ts)
Wrote 157 lines to frontend\src\lib\schemas.ts
import { z } from "zod";

/**
 * Every shape the API can hand us, parsed rather than asserted.
 *
 * These are the only place the frontend is allowed to believe something about the
 * backend's JSON. A `as CandidateResponse` would compile just as happily against a
 * response that changed last week; `parse` fails loudly at the point of entry, which is
 * the one place the failure is still cheap to understand.
 *
… +147 lines

● Background command "Start the backend with seed data" completed (exit code 0)

  Searched for 1 pattern, ran 1 shell command

The build already guards tests against this (user.timezone), but not bootRun — so tests pass while bootRun can't start. Same defect, same fix.

● Update(backend\build.gradle.kts)
Added 8 lines
    systemProperty("user.timezone", "UTC")
}

tasks.bootRun {
    // The same reason, and the same failure: without it, a developer whose machine is set
    // to one of the zone ids Postgres dropped cannot start the application at all, while
    // the tests above pass. The container image is already UTC, so this only ever matters
    // for running it straight from a workstation.
    systemProperty("user.timezone", "UTC")
}

// Leaves a single jar in build/libs so the Dockerfile's COPY glob is unambiguous.
tasks.jar {
    enabled = false

  Ran 1 shell command

Backend is up with seeded data. Let me capture the real response shapes to check my schemas against reality rather than against the Java I read.

  Ran 2 shell commands

Schemas match reality exactly. Now the BFF.

  Ran 1 shell command

● Write(frontend\src\lib\api.ts)
Wrote 40 lines to frontend\src\lib\api.ts
import "server-only";

/**
 * The only thing in this application that knows the API key exists.
 *
 * <p>`server-only` is not decoration: it turns "the browser must never talk to Spring
 * directly" from a convention into a build error. Import this from a client component and
 * the bundle fails rather than shipping the key to a browser, which is the one mistake in
 * this design that would be both silent and unrecoverable.
 */
… +30 lines

● Write(frontend\src\lib\rate-limit.ts)
Wrote 60 lines to frontend\src\lib\rate-limit.ts
import "server-only";

/**
 * A per-session budget in front of the backend's own tiers.
 *
 * <p>It is not a security boundary — the backend already limits by API key, and that is the
 * limit that protects the database. This one protects the *other* sessions: because every
 * browser shares one API key, a single tab stuck in a render loop would spend the whole
 * key's 60-per-minute search budget and every other recruiter would see 429s they did
 * nothing to cause. Splitting the budget per session turns that into one broken tab.
… +50 lines

● Write(frontend\src\app\api\[...path]\route.ts)
Wrote 117 lines to frontend\src\app\api\[...path]\route.ts
import { NextRequest, NextResponse } from "next/server";
import { randomUUID } from "node:crypto";
import { spring } from "@/lib/api";
import { forgetExpired, takeToken } from "@/lib/rate-limit";

/**
 * The BFF. Everything the browser asks for comes through here, gets the API key attached,
 * and is spent against a per-session budget on the way.
 *
 * <p>One handler rather than one per endpoint, because seven near-identical files would be
… +107 lines

● Update(frontend\src\app\api\[...path]\route.ts)
Added 2 lines, removed 1 line
}

async function proxy(request: NextRequest, path: string[]): Promise<NextResponse> {
  const target = `/api/v1/${path.join("/")}`;
  // The catch-all sits at /api, so `path` already begins with the API version.
  const target = `/api/${path.join("/")}`;
  if (!isAllowed(request.method, target)) {
    return problem(404, "not-found", `${request.method} ${target} is not exposed by this application
`);
  }

● Write(frontend\src\lib\client.ts)
Wrote 85 lines to frontend\src\lib\client.ts
import { z } from "zod";
import {
  Board,
  CandidatePage,
  Explain,
  Problem,
  Stage,
  StageEvent,
  Suggest,
} from "./schemas";
… +75 lines
Now the app shell and the RSC board load.

● Write(frontend\src\app\providers.tsx)
Wrote 30 lines to frontend\src\app\providers.tsx
"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { useState } from "react";

export function Providers({ children }: { children: React.ReactNode }) {
  // Created in state rather than at module scope: a module-level client would be shared
  // between requests on the server and leak one user's cache into another's render.
  const [client] = useState(
… +20 lines

● Write(frontend\src\app\layout.tsx)
Added 8 lines, removed 20 lines
import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});
const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Create Next App",
  description: "Generated by create next app",
  title: "Hiring pipeline",
  description: "One job opening, six stages, and a search box that explains itself.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        {children}
      <body className={`${geistSans.variable} ${geistMono.variable} antialiased`}>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
Found 1 new diagnostic issue in 1 file (ctrl+o to expand)

● Write(frontend\src\app\page.tsx)
Added 26 lines, removed 99 lines
import Image from "next/image";
import { spring } from "@/lib/api";
import { Board } from "@/lib/schemas";
import { Workspace } from "@/components/workspace";

export default function Home() {
  return (
    <div className="font-sans grid grid-rows-[20px_1fr_20px] items-center justify-items-center min-
h-screen p-8 pb-20 gap-16 sm:p-20">
      <main className="flex flex-col gap-[32px] row-start-2 items-center sm:items-start">
        <Image
          className="dark:invert"
          src="/next.svg"
          alt="Next.js logo"
          width={180}
          height={38}
          priority
        />
        <ol className="font-mono list-inside list-decimal text-sm/6 text-center sm:text-left">
          <li className="mb-2 tracking-[-.01em]">
            Get started by editing{" "}
            <code className="bg-black/[.05] dark:bg-white/[.06] font-mono font-semibold px-1 py-0.5
 rounded">
              src/app/page.tsx
            </code>
            .
          </li>
          <li className="tracking-[-.01em]">
            Save and see your changes instantly.
          </li>
        </ol>
/**
 * The board is fetched here, on the server, so the first paint already has it. That is the
 * one load worth doing this way: it is the screen a recruiter opens on, it needs no
 * interaction to be useful, and fetching it in the browser would mean a spinner on every
 * visit for data the server could have inlined.
 *
 * <p>Everything after this is client-side, because everything after this is a reaction to
 * her typing or clicking.
 */
export default async function Page() {
  const response = await spring("/api/v1/pipeline");
  if (!response.ok) {
    throw new Error(`The pipeline service answered ${response.status}`);
  }
  const board = Board.parse(await response.json());

        <div className="flex gap-4 items-center flex-col sm:flex-row">
          <a
            className="rounded-full border border-solid border-transparent transition-colors flex i
tems-center justify-center bg-foreground text-background gap-2 hover:bg-[#383838] dark:hover:bg-[#c
cc] font-medium text-sm sm:text-base h-10 sm:h-12 px-4 sm:px-5 sm:w-auto"
            href="https://vercel.com/new?utm_source=create-next-app&utm_medium=appdir-template-tw&u
tm_campaign=create-next-app"
            target="_blank"
            rel="noopener noreferrer"
          >
            <Image
              className="dark:invert"
              src="/vercel.svg"
              alt="Vercel logomark"
              width={20}
              height={20}
            />
            Deploy now
          </a>
          <a
            className="rounded-full border border-solid border-black/[.08] dark:border-white/[.145]
 transition-colors flex items-center justify-center hover:bg-[#f2f2f2] dark:hover:bg-[#1a1a1a] hove
r:border-transparent font-medium text-sm sm:text-base h-10 sm:h-12 px-4 sm:px-5 w-full sm:w-auto md
:w-[158px]"
            href="https://nextjs.org/docs?utm_source=create-next-app&utm_medium=appdir-template-tw&
utm_campaign=create-next-app"
            target="_blank"
            rel="noopener noreferrer"
          >
            Read our docs
          </a>
        </div>
      </main>
      <footer className="row-start-3 flex gap-[24px] flex-wrap items-center justify-center">
        <a
          className="flex items-center gap-2 hover:underline hover:underline-offset-4"
          href="https://nextjs.org/learn?utm_source=create-next-app&utm_medium=appdir-template-tw&u
tm_campaign=create-next-app"
          target="_blank"
          rel="noopener noreferrer"
        >
          <Image
            aria-hidden
            src="/file.svg"
            alt="File icon"
            width={16}
            height={16}
          />
          Learn
        </a>
        <a
          className="flex items-center gap-2 hover:underline hover:underline-offset-4"
          href="https://vercel.com/templates?framework=next.js&utm_source=create-next-app&utm_mediu
m=appdir-template-tw&utm_campaign=create-next-app"
          target="_blank"
          rel="noopener noreferrer"
        >
          <Image
            aria-hidden
            src="/window.svg"
            alt="Window icon"
            width={16}
            height={16}
          />
          Examples
        </a>
        <a
          className="flex items-center gap-2 hover:underline hover:underline-offset-4"
          href="https://nextjs.org?utm_source=create-next-app&utm_medium=appdir-template-tw&utm_cam
paign=create-next-app"
          target="_blank"
          rel="noopener noreferrer"
        >
          <Image
            aria-hidden
            src="/globe.svg"
            alt="Globe icon"
            width={16}
            height={16}
          />
          Go to nextjs.org →
        </a>
      </footer>
    </div>
  return (
    <main className="mx-auto flex min-h-screen max-w-[1600px] flex-col gap-6 p-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-xl font-semibold tracking-tight">Hiring pipeline</h1>
        <p className="text-sm text-muted-foreground">Backend Engineer</p>
      </header>
      <Workspace initialBoard={board} />
    </main>
import { ApiError, explain, suggest } from "@/lib/client";
import type { Explain, ExplainedNode, Span } from "@/lib/schemas";
import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
… +325 lines

● Update(frontend\src\lib\schemas.ts)
Added 3 lines
  didYouMean: z.array(z.string()).optional(),
  /** Illegal-transition failures only. */
  legalTargets: z.array(Stage).optional(),
  /** Stale-view failures only: what this tab believed, and what is actually true. */
  expectedCurrentStage: Stage.optional(),
  actualCurrentStage: Stage.optional(),
});
export type Problem = z.infer<typeof Problem>;

● Write(frontend\src\components\board.tsx)
Wrote 256 lines to frontend\src\components\board.tsx
"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, getBoard, transition } from "@/lib/client";
import type { Board as BoardData, Candidate, Stage } from "@/lib/schemas";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
… +251 lines

Now results, drawer, and the workspace that ties them together.

● Write(frontend\src\components\results.tsx)
Wrote 121 lines to frontend\src\components\results.tsx
"use client";

import { useQuery } from "@tanstack/react-query";
import { ApiError, getCandidates } from "@/lib/client";
import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

export function Results({
  query,
  onOpenCandidate,
… +113 lines

● Write(frontend\src\components\candidate-drawer.tsx)
Wrote 146 lines to frontend\src\components\candidate-drawer.tsx
"use client";

import { useQuery } from "@tanstack/react-query";
import { getEvents } from "@/lib/client";
import type { Board, Candidate, Stage } from "@/lib/schemas";
import { Badge } from "@/components/ui/badge";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
;
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
… +139 lines

● Write(frontend\src\components\workspace.tsx)
Wrote 63 lines to frontend\src\components\workspace.tsx
"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getBoard } from "@/lib/client";
import type { Board } from "@/lib/schemas";
import { BoardView } from "@/components/board";
import { CandidateDrawer } from "@/components/candidate-drawer";
import { Results } from "@/components/results";
import { SearchBar } from "@/components/search-bar";
… +53 lines

The suggestion chips need to rewrite the box, so the input has to be controlled from above. Restructuring that seam.

  Ran 2 shell commands

shadcn generated Base UI components, not Radix — different API (render instead of asChild). Let me see exactly what it produced.

  Searched for 1 pattern, ran 1 shell command

Base UI uses render, not asChild. Adapting the call sites and dropping the Radix packages I installed before knowing that.

  Ran 2 shell commands

Typechecks clean. Now the Vitest setup and the search-bar tests.

 Write(frontend\vitest.config.ts)
Wrote 12 lines to frontend\vitest.config.ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tsconfigPaths from "vite-tsconfig-paths";

export default defineConfig({
  plugins: [tsconfigPaths(), react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    include: ["src/**/*.test.tsx", "src/**/*.test.ts"],
… +2 lines

● Write(frontend\vitest.setup.ts)
Wrote 16 lines to frontend\vitest.setup.ts
import "@testing-library/jest-dom/vitest";

/**
 * jsdom implements neither, and Base UI's popups ask for both on mount. Without them the
 * tooltips around the interpretation chips throw and take the component down with them,
 * which would look like a failure of the thing actually under test.
 */
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
… +6 lines

● Write(frontend\src\components\search-bar.test.tsx)
Wrote 190 lines to frontend\src\components\search-bar.test.tsx
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SearchBar, segments } from "@/components/search-bar";
import type { Explain, Suggest } from "@/lib/schemas";

/**
… +181 lines

  Ran 1 shell command

RTL's auto-cleanup doesn't register without Vitest globals, so elements accumulated between tests.

● Update(frontend\vitest.setup.ts)
Added 7 lines
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Explicit, because Testing Library only registers its own afterEach when the test
// globals are injected, and this project keeps them off. Without it every render stays in
// the document and the second test finds two of everything.
afterEach(cleanup);

/**
 * jsdom implements neither, and Base UI's popups ask for both on mount. Without them the
 * tooltips around the interpretation chips throw and take the component down with them,

  Ran 2 shell commands

Real bug in my markup, not the test: the click handler was on a <button> nested inside the role="option", which is also wrong ARIA for a listbox.

● Update(frontend\src\components\search-bar.tsx)
Added 23 lines, removed 20 lines
            className="absolute z-20 mt-1 w-full overflow-hidden rounded-md border bg-popover shado
w-md"
          >
            {options.map((completion, index) => (
              <li key={completion.value} role="option" aria-selected={index === highlighted}>
                <button
                  type="button"
                  // mousedown, not click: blur fires first and would close the list before
                  // the click landed.
                  onMouseDown={(event) => {
                    event.preventDefault();
                    accept(index);
                  }}
                  onMouseEnter={() => setHighlighted(index)}
                  className={cn(
                    "flex w-full items-center justify-between px-3 py-1.5 text-left font-mono text-
sm",
                    index === highlighted && "bg-accent",
                  )}
                >
                  <span>{completion.value}</span>
                  <span className="text-xs text-muted-foreground">
                    {completion.kind === "FIELD" ? "field" : "value"}
                  </span>
                </button>
              // The option is the row itself, not a button inside it. A listbox option is
              // already its own control; nesting a button in one gives a screen reader two
              // things where there is one, and leaves the outer element inert to a click.
              <li
                key={completion.value}
                role="option"
                aria-selected={index === highlighted}
                // mousedown, not click: blur fires first and would close the list before
                // the click landed.
                onMouseDown={(event) => {
                  event.preventDefault();
                  accept(index);
                }}
                onMouseEnter={() => setHighlighted(index)}
                className={cn(
                  "flex cursor-pointer items-center justify-between px-3 py-1.5 font-mono text-sm",
                  index === highlighted && "bg-accent",
                )}
              >
                <span>{completion.value}</span>
                <span className="text-xs text-muted-foreground">
                  {completion.kind === "FIELD" ? "field" : "value"}
                </span>
              </li>
            ))}
          </ul>

  Ran 1 shell command

All 10 pass. Now the build — the board page must not be prerendered, since it's per-request data.

  Read 1 file, ran 1 shell command

● Update(frontend\src\app\page.tsx)
Added 7 lines, removed 7 lines
import { Workspace } from "@/components/workspace";

/**
 * Never prerendered. The board is a live view of who is where, so a copy baked at build
 * time would be wrong before it shipped — and the build would need a running backend and
 * the API key to produce it, which is a bad thing to need in order to compile.
 */
export const dynamic = "force-dynamic";

/**
 * The board is fetched here, on the server, so the first paint already has it. That is the
 * one load worth doing this way: it is the screen a recruiter opens on, it needs no
 * interaction to be useful, and fetching it in the browser would mean a spinner on every

 * <p>Everything after this is client-side, because everything after this is a reaction to
 * her typing or clicking.
 */
/**
 * Never prerendered. The board is a live view of who is where, so a copy baked at build
 * time would be wrong before it shipped — and the build would need a running backend and
 * the API key to produce it, which is a bad thing to need to compile.
 */
export const dynamic = "force-dynamic";

export default async function Page() {
  const response = await spring("/api/v1/pipeline");
  if (!response.ok) {

Now verifying end-to-end against the live backend, including the eight acceptance queries through the BFF.

  Ran 2 shell commands

Seven of eight return rows. The eighth is a real demo gap, not a UI bug: today is a Monday, since:monday means since midnight today, and the seed's most recent Interview move is 12 h before midnight — so it can never match on a Monday.

  Ran 5 shell commands

The old backend was still holding port 8080, so the restart never happened. Killing the listener and restarting cleanly.

  Ran 3 shell commands

All eight work end to end. Running the backend suite to check the seed change, while I write the Docker wiring.

  Ran 1 shell command

● Write(frontend\Dockerfile)
Wrote 31 lines to frontend\Dockerfile
# Dependencies in their own layer, so editing a component does not reinstall the world.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules node_modules
COPY . .
… +21 lines

Update(docker-compose.yml)
Added 25 lines
        condition: service_healthy
      redis:
        condition: service_healthy
    healthcheck:
      # Not for the backend's own sake — it is so the frontend can wait for a backend that
      # can actually answer. Without it the first page load races the migrations and the
      # seed, and "docker compose up" shows an error page on a system that is merely
      # still starting.
      test: ["CMD-SHELL", "wget -q -O - http://localhost:8080/actuator/health | grep -q UP"]
      interval: 5s
      timeout: 5s
      retries: 30
      start_period: 40s

  frontend:
    build: ./frontend
    environment:
      # Service name, not localhost: this reaches Spring over the compose network, and
      # the browser never does.
      API_BASE_URL: http://backend:8080
      # The same key the backend expects, held only here. It is never sent to the browser.
      API_KEY: ${API_KEY:-change-me-local-only}
      SESSION_RATE_LIMIT: ${SESSION_RATE_LIMIT:-240}
    ports:
      - "3000:3000"
    depends_on:
      backend:
        condition: service_healthy

volumes:
  postgres-data:

  Ran 3 shell commands

● Background command "Backend suite after seed and API changes" completed (exit code 0)

Backend: 437 tests, 436 passed, 1 skipped (the documented harness). Lint clean. Now verifying the BFF's guarantees and the 409 path.

  Ran 3 shell commands

Now the full docker compose up. Stopping my local dev servers first and building in the background.

  Ran 2 shell commands

● Write(frontend\README.md)
Added 50 lines, removed 23 lines
This is a [Next.js](https://nextjs.org) project bootstrapped with [`create-next-app`](https://nextjs
.org/docs/app/api-reference/cli/create-next-app).
# Frontend

## Getting Started
Next.js 15 App Router, TypeScript, Tailwind, shadcn/ui, TanStack Query, Zod.

First, run the development server:

```bash
npm run dev
# or
yarn dev
# or
pnpm dev
# or
bun dev
```
make up                  # the whole stack, seeded, on http://localhost:3000
cd frontend && npm test  # the search bar
```

Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.
## One screen

You can start editing the page by modifying `app/page.tsx`. The page auto-updates as you edit the fi
le.
The search box sits above either the board or the results — never both, because a board that
is not filtered by the query above it is a board that contradicts it.

This project uses [`next/font`](https://nextjs.org/docs/app/building-your-application/optimizing/fon
ts) to automatically optimize and load [Geist](https://vercel.com/font), a new font family for Verce
l.
The query lives in the URL (`/?q=stage:offer`). That is the same argument the backend made
for spelling the looser name match as a field rather than a request flag: whatever produced
these results should be visible in, and reproducible from, the thing you can copy. It is
written with `history.replaceState` rather than a router push, so a settled keystroke does
not re-run the server render of a board that has not changed.

## Learn More
## The BFF

To learn more about Next.js, take a look at the following resources:
The browser never talks to Spring. Every request goes to `/api/…`, which is a single Route
Handler that attaches the API key and spends a per-session budget on the way.

- [Next.js Documentation](https://nextjs.org/docs) - learn about Next.js features and API.
- [Learn Next.js](https://nextjs.org/learn) - an interactive Next.js tutorial.
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

You can check out [the Next.js GitHub repository](https://github.com/vercel/next.js) - your feedback
 and contributions are welcome!
### The session limit is in process, and that is a real limit

## Deploy on Vercel
`SESSION_RATE_LIMIT` (240/minute) is kept in a `Map` in the Node process. It resets on
restart and is **not shared between replicas** — a second frontend container would give each
session two budgets.

The easiest way to deploy your Next.js app is to use the [Vercel Platform](https://vercel.com/new?ut
m_medium=default-template&filter=next.js&utm_source=create-next-app&utm_campaign=create-next-app-rea
dme) from the creators of Next.js.
It is not a security boundary: the backend already limits by API key, and that is the limit
protecting the database. This one protects the *other* sessions, because every browser here
shares one key — without it a single tab stuck in a render loop would spend the whole key's
60-per-minute search budget and everyone else would see 429s they did nothing to cause.
Splitting it per session turns that into one broken tab. Scaling past one container means
moving it to the Redis the backend already uses for the same job.

Check out our [Next.js deployment documentation](https://nextjs.org/docs/app/building-your-applicati
on/deploying) for more details.
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

  Ran 3 shell commands

● Background command "Build and start the whole stack" completed (exit code 0)

● Background command "Wait for the composed stack to come up" completed (exit code 0)

All four services up. Verifying the composed stack end to end.

  Ran 5 shell commands

A few orphans my change created. Checking before removing.

  Ran 2 shell commands

public/ is now empty, and git won't track an empty directory — so that COPY would break a fresh clone. Removing both.

  Ran 4 shell commands

Done. docker compose up gives a working, seeded app on :3000, and all eight acceptance queries work end to end from the UI.

Backend 437 tests: 436 passed, 1 skipped (ExplainPassTest, the documented perf harness), 0 failed. Frontend 10 tests passed; lint, tsc --noEmit and next build all clean.

The two decisions you made

legalTargets on CandidateResponse — the board renders one button per legal target and knows no rules at all. Empty means terminal, which is why it's not optional: it's how the card knows to show a disabled button explaining "Rejected is a terminal stage — this candidate's history is complete and cannot be moved on." Three new backend tests pin the contract, including that the board carries it on every card.

One route, results replace the board — with ?q= in the URL, written via replaceState so a settled keystroke doesn't re-run the server render of an unchanged board.

What I'd flag

A seed bug that only bites on Mondays. Seven of the eight acceptance queries returned rows on the first run; "Who moved to Interview since Monday?" returned zero. Not a UI fault — since:monday resolves to midnight today when today is Monday, and the seed anchored its recent Interview moves to midnight and subtracted 12–48 h, so the newest was always yesterday. One day in seven, that query parsed correctly, ran correctly, and truthfully found nobody. I anchored that one group to the clock instead (2 h ago, spreading back to 38 h), which keeps both "within three days" and "since Monday" true on any day. It's the only place the seed reads the clock rather than the day, because it's the only place the day is what's being asked about.

bootRun couldn't start on this machine. The build already pins user.timezone=UTC for tests — because pgjdbc forwards legacy zone ids that Postgres 16 refuses — but not for bootRun, so the tests passed while the app wouldn't boot. Same defect, same three-line fix.

shadcn's current default is Base UI, not Radix. I'd pre-installed @radix-ui/* on the assumption it was Radix; shadcn init generated @base-ui/react components using render rather than asChild. I adapted the call sites and removed the four packages I'd installed in error. Focus trapping and restore-to-trigger come from Base UI's Dialog rather than anything hand-rolled.

Worth knowing about the search bar

Highlighting is driven entirely by server-supplied spans — predicate positions from /explain, the error range from the 422 — and it has to be: the normaliser rewrites "for more than a week" into in_stage_for:>7d before parsing, so the characters a predicate corresponds to simply aren't derivable from the text on screen.

That produced one test I'd call the most valuable of the ten: the bar draws nothing while the answer on hand describes older text. Without that guard a stale response paints last keystroke's offsets over the current string and underlines the wrong characters — plausible-looking, and exactly the failure the feature exists to prevent.

Two smaller things: public/ is gone (the five scaffold SVGs became orphans when I replaced page.tsx, and an empty dir git won't track would have broken the Docker COPY on a fresh clone), and make test now runs both halves rather than just the backend.

The per-session limiter is in-process — it resets on restart and isn't shared across replicas. That's written down in frontend/README.md as a tripwire rather than left implicit, since a second frontend container would silently give each session two budgets.
 -->

