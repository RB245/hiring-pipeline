# 10 - Documentation: README, ADRs, architecture PDF

Prompt given: `prompts/10-*.md`

---

<!--Big final pass. Let me start with the part you most want to know about — the source:referral test — and check tooling feasibility for the GIF and PDF in parallel, since both may constrain the plan.

  Ran 2 shell commands

Chrome is available and mermaid-cli exists (no ffmpeg or pandoc). Now the test you actually care about. Writing SourceField first to see what it costs.

  Ran 1 shell command

Now the variant, to see the exact blast radius the sealed model forces.

● Update(backend\src\main\java\com\pipeline\search\ResolvedValue.java)
Added 12 lines
    record StatusValue(Status status) implements ResolvedValue {}

    /**
     * A label, compared exactly.
     *
     * <p>Distinct from {@link TextValue} because the difference is not cosmetic. Text is
     * fuzzy and identifies a person, so it feeds the nameMatch term of the ranking and is
     * explained as "name ~ 'sharam' (0.80)". A label is a value someone picked from a short
     * list; matching it approximately would be wrong, and scoring a person's name against
     * it would be nonsense. Reusing TextValue for {@code source:referral} would quietly do
     * both — every result would carry a name score against the word "referral".
     */
    record ExactValue(String text) implements ResolvedValue {}

    /**
     * Fuzzy text, and how forgivingly it is read. Three shapes rather than a pair of
     * booleans, because they are three ways of asking the same question and {@code
     * /explain} has to be able to say which one she asked.

  Ran 1 shell command

One production file forced so far. Adding the case it demands.

● Update(backend\src\main\java\com\pipeline\search\Leaf.java)
Added 3 lines
        return switch (value) {
            case ResolvedValue.StageValue stage -> handler.field() + is + title(stage.stage().name()
);
            case ResolvedValue.StatusValue status -> handler.field() + is + title(status.status().na
me());
            // Not title-cased: a label is reported as it is stored, and "Careers-page"
            // would be this layer editing data it did not choose.
            case ResolvedValue.ExactValue exact -> handler.field() + is + exact.text();
            case ResolvedValue.TextValue text ->
                    handler.field() + (negated ? " !~ " : " ~ ") + "'" + text.text() + "'";
            case ResolvedValue.AgeValue age ->

  Ran 1 shell command

● Update(backend\src\main\java\com\pipeline\api\ExplainResponse.java)
Added 1 line
            return switch (value) {
                case ResolvedValue.StageValue stage -> "the stage " + title(stage.stage().name());
                case ResolvedValue.StatusValue status -> title(status.status().name());
                case ResolvedValue.ExactValue exact -> "exactly \"" + exact.text() + "\"";
                case ResolvedValue.TextValue text -> switch (text.match()) {
                    case NAME -> "matched fuzzily against the name";
                    case IDENTITY -> "matched fuzzily against the name, and exactly against the emai
l";

  Ran 2 shell commands

The sealed value model forced exactly three one-line switch arms. Now registering it in the non-Spring test mirror, which would otherwise test a different field set than the app has.

  Ran 1 shell command

FieldRegistryTest used source as its hypothetical new field back in file 07 — now that the field is real, the registry's duplicate check will fire. Renaming the hypothetical.

  Ran 1 shell command

Now an end-to-end test for the new field — through parse, SQL and HTTP, not just the parser boundary.

  Ran 2 shell commands

source:referral works end to end — 50 results, correct explanation, and autocomplete picked it up with no extra wiring. Now measuring coverage before I pick the gate.

  Ran 1 shell command

Domain 100%, search 97.4% — but search.fields is 80.7% on lines. Let me see what's actually uncovered before choosing how to gate.

  Ran 1 shell command

Those are real gaps, not padding: the < direction of the age comparison — the one the code comments warn is easy to invert — is untested, as is the before: bound. Writing those.

  Ran 2 shell commands

Legitimate failure: nobody in 
  Ran 1 shell command

Now the coverage gate, with the numbers to set it against.

  Ran 1 shell command

83.3% — short by 1.7. The rest is valueKind()/examples(), which feed error messages and autocomplete. There's a real invariant there worth asserting rather than a number to pad.

● Write(backend\src\test\java\com\pipeline\search\FieldContractTest.java)
Wrote 75 lines to backend\src\test\java\com\pipeline\search\FieldContractTest.java
package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.pipeline.search.fields.SearchFixture;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
… +66 lines

  Ran 1 shell command

Gate passes at 100% / 97.4% / 96.5%. Now the two npm warnings.

  Ran 1 shell command

Warning-free. Before I commit to an approach for the PDF and the GIF, let me verify the rendering toolchain actually works here.

  Ran 1 shell command

mermaid-cli renders with the system Chrome, so the PDF pipeline works. One genuinely open decision left, with a big effort difference.

● User answered Claude's questions:
· The README's first item is a demo GIF. I can't screen-record, but I can drive the running app with headless Chrome. How do you want it produced? → Scripted GIF, committed generator (Recommended)

Building the generator. Putting it in its own package so the app's dependency list stays honest.

  Ran 1 shell command

● Write(docs\demo\record-demo.mjs)
Wrote 143 lines to docs\demo\record-demo.mjs
/**
 * Regenerates the demo GIF in the README by driving the running application.
 *
 * Committed rather than the GIF alone, for the same reason the architecture diagrams are
 * Mermaid sources rather than pasted images: an artefact nobody can regenerate goes stale
 * silently, and the first thing a reviewer sees is the worst place for that.
 *
 *   docker compose up -d        # the app it records has to be running
 *   cd docs/demo && npm install && npm run record
 *
… +133 lines

  Ran 2 shell commands

1.32 MB for 60 frames. Let me actually look at what it captured rather than assume.

  Read 1 file, ran 2 shell commands

Board renders well — but at 1180px only three columns show (the six-column breakpoint is 1280). Checking the other frames before I widen the recording.

  Read 1 file

A real bug, found by running it: "Limit of 60 per window exceeded". suggest is keyed on the raw text, not the debounced value — so it fires once per keystroke and a 50-character sentence spends the whole search budget.

  Ran 1 shell command

● Update(frontend\src\components\search-bar.tsx)
Added 7 lines, removed 3 lines
    retry: false,
  });

  // Keyed on the settled text, like the parse above. Keyed on the raw text it fired once
  // per keystroke, and a fifty-character sentence spent the backend's whole 60-a-minute
  // search budget before she had finished typing it — the debounce was there but this
  // query was not behind it.
  const completions = useQuery({
    queryKey: ["suggest", text],
    queryFn: () => suggest(text),
    enabled: completionsOpen && text.length > 0,
    queryKey: ["suggest", settled],
    queryFn: () => suggest(settled),
    enabled: completionsOpen && settled.length > 0,
    retry: false,
  });


● Update(frontend\src\components\search-bar.tsx)
Added 4 lines, removed 1 line
  const tokenSpans = current && explained ? leafSpans(explained.ast) : [];
  const chips = current && explained ? interpret(explained.ast) : [];

  const options = completions.data?.completions ?? [];
  // Same staleness rule as the highlighting, and for the same reason: `replacing` is a
  // range into the text that was sent, so splicing a completion into text she has since
  // typed more of would land it in the wrong place.
  const options = current ? (completions.data?.completions ?? []) : [];

  function accept(index: number) {
    const completion = options[index];

  Ran 1 shell command
  Pinning the bug so it can't come back.

● Update(frontend\src\components\search-bar.test.tsx)
Added 23 lines
  });

  /**
   * Both server calls sit behind the one debounce, and this is the test that says so.
   *
   * <p>They did not. The parse was debounced and the completions were keyed on the raw
   * text, so autocomplete fired once per keystroke — typing a fifty-character sentence
   * spent the backend's whole sixty-a-minute search budget before the sentence was
   * finished, and the box answered with a rate-limit error instead of results. It was
   * found by watching the app, not by any test here, because every test typed into a
   * mock that never complained.
   */
  it("asks the server once the typing settles, not once per keystroke", async () => {
    explainMock.mockRejectedValue(unknownStage());

    render(<Harness />);
    await userEvent.type(box(), "stage:screening in_st", { delay: 10 });
    await waitFor(() => expect(explainMock).toHaveBeenCalled());

    // Twenty-one characters. Anything near that many calls means a query escaped the
    // debounce; a couple is the settle plus whatever was in flight as it landed.
    expect(suggestMock.mock.calls.length).toBeLessThanOrEqual(3);
    expect(explainMock.mock.calls.length).toBeLessThanOrEqual(3);
  });

  /**
   * The failure this guards against is subtle and would be invisible in a screenshot: a
   * stale answer describing the previous keystroke, drawn over the current text, underlines
   * whatever characters happen to sit at those offsets now.

  Read 1 file, ran 2 shell commands

The debounce fix worked — no rate-limit error, and the highlighting, chips and scores all read correctly. Checking the error and zero-result frames.

  Read 1 file

Red underline lands exactly on Intervew — the API's span, not a client guess.

  Read 1 file

All four screens verified. Recording the final GIF and removing the scratch script.

  Ran 1 shell command

2.26 MB is heavy for a README. The held frames are duplicates — one frame with a longer delay says the same thing.

  Ran 1 shell command

1.13 MB. Now the architecture document — Mermaid sources committed, PDF rendered from them. Consolidating the doc tooling into one package first.

  Ran 2 shell commands

Now the architecture document — Mermaid sources, committed.

● Write(docs\architecture.md)
Wrote 284 lines to docs\architecture.md
# Hiring pipeline — architecture

One job opening, six stages, an append-only history, and a search box that explains itself.

Source: <https://github.com/RB245/hiring-pipeline>

This document is the source for `architecture.pdf`. The diagrams are Mermaid, so they are
diffable and editable rather than pasted images; `cd docs/tooling && npm run pdf` rebuilds
the PDF from this file.
… +274 lines

● Write(docs\tooling\render-pdf.mjs)
Wrote 125 lines to docs\tooling\render-pdf.mjs
/**
 * Renders docs/architecture.pdf from docs/architecture.md.
 *
 * The diagrams stay Mermaid in a markdown file that is the version-controlled source; the
 * PDF is a build output. mermaid-cli turns the code blocks into SVGs in one pass, marked
 * turns the prose into HTML, and Chrome prints it. Nothing here is hand-placed, so the
 * document cannot drift from the diagrams the way a folder of exported PNGs does.
 *
 *   cd docs/tooling && npm install && npm run pdf
 *
… +118 lines

  Ran 2 shell commands

npx.cmd can't be spawned directly on Node 22 (Windows). Making the tooling self-contained instead of shelling out to npx.

  Ran 3 shell commands

PDF rendered. Let me check it — I only see four SVGs listed for five diagrams.

  Read 1 file, ran 1 shell command

5 pages, all 5 diagrams rendered. Let me actually look at it rather than trust the counts.

  Read 1 file, ran 1 shell command

The PDF is rendering dark-on-dark — the prose is effectively invisible. My CSS sets a colour but no background, so Chrome is applying a dark colour-scheme default.

  Read 1 file, ran 1 shell command

Readable now — white background, all five diagrams, six sections. Cleaning up and writing the ADRs.

  Listed 2 directories, ran 2 shell commands

● Write(docs\adr\0004-projection-and-reached-mask.md)
Wrote 61 lines to docs\adr\0004-projection-and-reached-mask.md
# ADR 0004 — A denormalised projection, and `reached_mask`

Status: accepted

## Context

`stage_event` is the source of truth, and the two questions the product exists to answer
are awkward against it:

- *Where is this candidate now?* — the last event, per candidate.
… +51 lines

● Write(docs\adr\0005-keyset-pagination.md)
Wrote 53 lines to docs\adr\0005-keyset-pagination.md
# ADR 0005 — Keyset pagination, not OFFSET

Status: accepted

## Context

The candidate list and the search results both page. `LIMIT ... OFFSET ...` is the obvious
implementation and is wrong in a way that is easy to miss in testing: a row inserted while
the recruiter is between pages shifts everything down, so page two re-shows a row she has
already seen, or skips one she never did.
… +43 lines

● Write(docs\adr\0006-tiered-rate-limiting.md)
Wrote 59 lines to docs\adr\0006-tiered-rate-limiting.md
# ADR 0006 — Rate limits tiered by cost

Status: accepted

## Context

The API needs limiting. One global number per key is the usual answer and it forces a bad
choice: set it low enough to protect the expensive endpoint and an ordinary board refresh
starts failing; set it high enough for comfortable browsing and a search loop can hammer
the database unopposed.
… +49 lines

Now the grammar reference. Let me pull the real values from the code rather than transcribing them from memory.

  Ran 1 shell command

Let me generate the error catalogue from the running API rather than transcribing it.

  Ran 3 shell commands

Now the grammar reference, written from the verified behaviour.

● Write(docs\search-grammar.md)
Wrote 184 lines to docs\search-grammar.md
# The search box

Everything it accepts, and everything it will say when it cannot.

You can type a sentence or the query language; they end up at the same place. Whatever you
type, `GET /api/v1/search/explain?q=…` shows exactly how it was read — including what
"since Monday" resolved to — and the box shows the same thing as chips underneath it.

---
… +177 lines

Now CI. One thing I can't do honestly: there's no Java formatter configured, and adding one would reformat every file.

● Write(.github\workflows\ci.yml)
Added 42 lines, removed 7 lines
      # one on every run.
      - uses: gradle/actions/wrapper-validation@v4
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew build

      # `build` runs the whole suite against a real Postgres via Testcontainers — Docker
      # is present on ubuntu-latest, so nothing needs starting here — and `check` also
      # pulls in the two below.
      #
      # The backend's lint is architectural rather than stylistic: ArchUnit fails the
      # build if the domain reaches for Spring, JPA or an outer layer, if a use case
      # reaches for an adapter, or if the query pipeline learns a field's name. There is
      # deliberately no Java formatter; adding one now would reformat every file in the
      # repository to satisfy a tool that has never had an opinion about it, which is
      # churn rather than a check. See the README.
      - name: Build, test and verify architecture
        run: ./gradlew build
        working-directory: backend

      # Gated at 85% on the domain and the search packages only. Everywhere else is
      # adapters and wiring, where the number measures how much Spring was started.
      - name: Coverage gate (domain and search)
        run: ./gradlew jacocoTestCoverageVerification
        working-directory: backend

      - name: Keep the coverage report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: backend/build/reports/jacoco/test/html
          retention-days: 7

  frontend:
    runs-on: ubuntu-latest
    defaults:

          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - run: npm ci
      - run: npm run lint
      - run: npx tsc --noEmit
      - run: npm test
      # The build is the only check that exercises the server/client split: an accidental
      # import of the API key module from a client component fails here and nowhere else.
      - run: npm run build

      - name: Lint
        run: npm run lint

      - name: Types
        run: npx tsc --noEmit

      - name: Test
        run: npm test

      # The only check that exercises the server/client split: an accidental import of
      # the API-key module from a client component fails here and nowhere else, because
      # `server-only` is a build-time error rather than a runtime one.
      - name: Build
        run: npm run build
        env:
          API_KEY: build-time-placeholder

  Ran 1 shell command

492 backend tests, 491 passing. Now the README.

● Write(README.md)
Wrote 340 lines to README.md
# Hiring pipeline

One job opening, six stages, an append-only history, and a search box that explains itself.

![Searching, being understood, being corrected, and moving a candidate](docs/demo.gif)

## Run it

```bash
docker compose up
… +330 lines


Final verification. First, source:referral through the actual UI rather than just HTTP.

  Read 1 file, ran 1 shell command

source:referral stage:offer — both tokens highlighted, 5 results, correctly scoped and ranked. Cleaning up and verifying every README link resolves.

  Ran 1 shell command

ADR 0001 has no link, and the Makefile has no target for regenerating the doc artefacts. Fixing both.

  Ran 1 shell command

Now the final verification pass across everything.

  Ran 4 shell commands

Everything green, nothing committed.

Done

┌─────────────┬────────────────────────────────────────────────────────────────────────────────────────────────┐
│             │                                                                                                │
├─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Backend     │ 492 tests, 491 passed, 1 skipped, 0 failed                                                     │
├─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Frontend    │ 11 tests passed, lint clean, tsc clean, npm test warning-free                                  │
├─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Coverage    │ domain 100%, search 97.4%, search.fields 96.5% — gate 85%, wired into check                    │
│ gate        │                                                                                                │
├─────────────┼────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Artefacts   │ docs/demo.gif (1.1 MB, regenerable), docs/architecture.pdf (5 pages, rendered from committed   │
│             │ Mermaid), 6 ADRs, docs/search-grammar.md                                                       │
└─────────────┴────────────────────────────────────────────────────────────────────────────────────────────────┘

The open/closed test — and where it didn't hold

source:referral works end to end. 50 results through parse, SQL, ranking and HTTP; /explain says exactly "referral"; autocomplete picked it up with no wiring. Verified in the real UI, not just over curl.

The lexer, parser, validator, normaliser, SpecificationBuilder, Ranking and search service were untouched — your literal criterion passed. But "one new class, nothing else" did not hold exactly, and you should have the precise footprint:

- 1 new class — SourceField
- +1 sealed ResolvedValue variant and the 2 exhaustive switches it forces (Leaf.describe, ExplainResponse.means) — three one-line arms

source is the first field whose value is an exact label rather than a person's name. I could have reused TextValue and had genuinely one file — but that value feeds the nameMatch term, so every candidate's name would have been scored against the word "referral" and the explanation would have read source ~ 'referral' (0.00). The three edits are the sealed model doing what it was built for: a new kind of value is a compile error where it must be handled. A new field whose value fits an existing shape really is one class.

One test changed for an honest reason: FieldRegistryTest used source as its hypothetical field back in file 07, so making it real tripped the registry's duplicate check. It's cohort now.

A bug the rate limiter found

Recording the GIF, the app answered "Limit of 60 per window exceeded" mid-sentence. suggest was keyed on the raw input, not the debounced value — firing once per keystroke, so a fifty-character sentence spent the whole search budget before it was finished. The debounce existed; that one query wasn't behind it. Fixed, plus the same staleness guard the highlighting already had, and pinned with a test. Every existing test typed into a mock that never complained; your 60/min tier is what surfaced it. It's written up in the README and ADR 0006.

Two things I decided rather than asked

The backend has no code formatter, so CI's backend "lint" is ArchUnit. Adding Spotless would reformat every file to satisfy a tool that's never had an opinion about this repo — churn, not a check. Stated plainly in CI and the README so it reads as a decision rather than a gap. Say the word if you'd rather I add one.

Two coverage gaps were real, and I tested them rather than padding. search.fields was 80.7%; the uncovered code was the < direction of the age comparison — the exact logic whose own comment warns that inverting it is the easy mistake and that nothing exercised — plus the before: bound and the modifier-throws contract. One of those new tests immediately failed by comparing two empty sets, which was the "test that can't fail" pattern again, caught by the isNotEmpty() I'd put beside it.

Also worth flagging

The PDF first rendered dark grey on black — Chrome applies the machine's dark-mode preference when a document sets a colour but no background. Legible while writing, unreadable as a PDF. Caught by screenshotting the render instead of trusting the page count; fixed with an explicit color-scheme: only light.

The README's "material worth writing up" covers all of it: the three checks that couldn't fail, the two bugs found by running rather than testing (plus this third), the 141 ms → 3.0 s correction with the decision re-affirmed on the true number, the index that's 87× faster and 3.7× slower, and a plain statement that every millisecond figure comes from ExplainPassTest, which does not run in a normal build.
   -->

