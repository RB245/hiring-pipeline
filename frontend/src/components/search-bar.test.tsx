import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SearchBar, segments } from "@/components/search-bar";
import type { Explain, Suggest } from "@/lib/schemas";

/**
 * The search bar, which is the only component here with logic worth testing. Everything
 * else on the screen renders what it is handed; this one decides what to underline, and
 * getting that wrong points a recruiter at the wrong characters of her own sentence.
 *
 * <p>The API is mocked, deliberately including the spans. What is under test is that the
 * component uses the offsets it was given rather than working them out again — so the
 * fixtures carry real spans taken from the running backend, and the assertions check the
 * exact characters they land on.
 */

vi.mock("@/lib/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/client")>()),
  explain: vi.fn(),
  suggest: vi.fn(),
}));

const { explain, suggest, ApiError } = await import("@/lib/client");
const explainMock = vi.mocked(explain);
const suggestMock = vi.mocked(suggest);

function Harness({ initial = "" }: { initial?: string }) {
  const [text, setText] = useState(initial);
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <TooltipProvider>
        <SearchBar value={text} onChange={setText} onCommit={() => {}} />
      </TooltipProvider>
    </QueryClientProvider>
  );
}

/** Exactly what the backend returns for `stage:Intervew`, spans included. */
function unknownStage() {
  return new ApiError(422, {
    type: "https://pipeline.example/problems/unknown-stage",
    title: "Cannot run that search",
    status: 422,
    detail: 'There is no stage called "Intervew".',
    code: "UNKNOWN_STAGE",
    span: [6, 14],
    didYouMean: ["Interview"],
  });
}

const box = () => screen.getByRole("combobox");

beforeEach(() => {
  vi.clearAllMocks();
  suggestMock.mockResolvedValue({ replacing: [0, 0], completions: [] } satisfies Suggest);
});

describe("segments", () => {
  it("splits the text at the boundaries it is given", () => {
    expect(segments("stage:Intervew", [], [6, 14])).toEqual([
      { text: "stage:", kind: "plain" },
      { text: "Intervew", kind: "error" },
    ]);
  });

  it("leaves text alone when there is nothing to mark", () => {
    expect(segments("anything", [], undefined)).toEqual([{ text: "anything", kind: "plain" }]);
  });

  /**
   * The normaliser rewrites her sentence before parsing it, so a span can in principle
   * arrive that does not fit the text on screen. Drawing it anyway would slice the string
   * at an offset that means nothing.
   */
  it("ignores a range that does not fit the text", () => {
    expect(segments("short", [], [2, 99])).toEqual([{ text: "short", kind: "plain" }]);
  });
});

describe("SearchBar", () => {
  it("renders the message the API gave for a query it could not parse", async () => {
    explainMock.mockRejectedValue(unknownStage());

    render(<Harness />);
    await userEvent.type(box(), "stage:Intervew");

    expect(await screen.findByRole("alert")).toHaveTextContent('There is no stage called "Intervew".');
  });

  it("underlines exactly the characters the span covers", async () => {
    explainMock.mockRejectedValue(unknownStage());

    const { container } = render(<Harness />);
    await userEvent.type(box(), "stage:Intervew");

    await waitFor(() => expect(container.querySelector('[data-kind="error"]')).not.toBeNull());
    // [6,14] of "stage:Intervew" is the misspelt stage and nothing else — not the field
    // in front of it, and not the colon.
    expect(container.querySelector('[data-kind="error"]')).toHaveTextContent("Intervew");
    expect(container.querySelector('[data-kind="plain"]')).toHaveTextContent("stage:");
  });

  it("rewrites the query in place when a suggestion chip is clicked", async () => {
    explainMock.mockRejectedValue(unknownStage());

    render(<Harness />);
    await userEvent.type(box(), "stage:Intervew");
    await screen.findByRole("alert");

    await userEvent.click(screen.getByRole("button", { name: "Interview" }));

    // Spliced over [6,14] rather than appended or retyped, so the correction lands on the
    // part that was wrong and leaves the rest of her sentence alone.
    expect(box()).toHaveValue("stage:Interview");
  });

  it("shows a chip per condition once the query parses", async () => {
    explainMock.mockResolvedValue({
      query: "stage:screening in_stage_for:>7d",
      dsl: "stage:screening in_stage_for:>7d",
      ast: {
        type: "and",
        source: [0, 32],
        children: [
          { type: "predicate", field: "stage", operator: "", value: "screening", source: [0, 15] },
          { type: "predicate", field: "in_stage_for", operator: ">", value: "7d", source: [16, 32] },
        ],
      },
    } satisfies Explain);

    render(<Harness />);
    await userEvent.type(box(), "stage:screening in_stage_for:>7d");

    expect(await screen.findByText("Stage = Screening")).toBeInTheDocument();
    expect(screen.getByText("In stage for > 7d")).toBeInTheDocument();
  });

  it("reads a negated condition as a negation rather than a match", async () => {
    explainMock.mockResolvedValue({
      query: "-status:rejected",
      dsl: "-status:rejected",
      ast: {
        type: "not",
        source: [0, 16],
        children: [{ type: "predicate", field: "status", operator: "", value: "rejected", source: [1, 16] }],
      },
    } satisfies Explain);

    render(<Harness />);
    await userEvent.type(box(), "-status:rejected");

    expect(await screen.findByText("Not Status = Rejected")).toBeInTheDocument();
  });

  it("splices a completion over the range the API said it was completing", async () => {
    explainMock.mockRejectedValue(unknownStage());
    suggestMock.mockResolvedValue({
      replacing: [0, 8],
      completions: [{ value: "stage:interview", label: "interview", kind: "VALUE" }],
    } satisfies Suggest);

    render(<Harness />);
    await userEvent.type(box(), "stage:in");

    await userEvent.click(await screen.findByRole("option", { name: /stage:interview/ }));

    expect(box()).toHaveValue("stage:interview");
  });

  /**
   * The failure this guards against is subtle and would be invisible in a screenshot: a
   * stale answer describing the previous keystroke, drawn over the current text, underlines
   * whatever characters happen to sit at those offsets now.
   */
  it("draws nothing while the answer on hand describes older text", async () => {
    explainMock.mockRejectedValue(unknownStage());

    const { container } = render(<Harness />);
    await userEvent.type(box(), "stage:Intervew");
    await waitFor(() => expect(container.querySelector('[data-kind="error"]')).not.toBeNull());

    await userEvent.type(box(), "wwwwww");

    expect(container.querySelector('[data-kind="error"]')).toBeNull();
  });
});
