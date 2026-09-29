import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { BoardView } from "@/components/board";
import type { Board } from "@/lib/schemas";

/**
 * Adding a candidate, which is the only thing on the board with logic rather than layout:
 * it inserts optimistically, unwinds if the post fails, and has to put a duplicate-email
 * message somewhere she will actually see it.
 */

vi.mock("@/lib/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/client")>()),
  getBoard: vi.fn(),
  createCandidate: vi.fn(),
  transition: vi.fn(),
}));

const { createCandidate, getBoard, ApiError } = await import("@/lib/client");
const createMock = vi.mocked(createCandidate);
const boardMock = vi.mocked(getBoard);

const EMPTY_BOARD: Board = {
  columns: (["APPLIED", "SCREENING", "INTERVIEW", "OFFER", "HIRED", "REJECTED"] as const).map((stage) => ({
    stage,
    count: 0,
    candidates: [],
  })),
};

function renderBoard() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <TooltipProvider>
        <BoardView initialBoard={EMPTY_BOARD} onOpenCandidate={() => {}} />
      </TooltipProvider>
    </QueryClientProvider>,
  );
}

// hidden: true because the dialog is modal and correctly marks the board behind it
// inert. The column is still in the document, which is where the optimistic card lands.
const applied = () => screen.getByRole("region", { name: /^Applied/, hidden: true });

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Add candidate" }));
  await screen.findByRole("dialog");
  await user.type(screen.getByLabelText("Full name"), "Nadia Rahman");
  await user.type(screen.getByLabelText("Email"), "nadia@example.com");
  await user.type(screen.getByLabelText("Source"), "referral");
}

beforeEach(() => {
  vi.clearAllMocks();
  boardMock.mockResolvedValue(EMPTY_BOARD);
});

describe("adding a candidate", () => {
  it("posts what was typed and shows the card in Applied", async () => {
    const user = userEvent.setup();
    createMock.mockImplementation(() => new Promise(() => {})); // never settles: the optimistic card is the subject

    renderBoard();
    await fillAndSubmit(user);
    await user.click(screen.getByRole("button", { name: "Add to pipeline" }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith({
        fullName: "Nadia Rahman",
        email: "nadia@example.com",
        phone: undefined,
        source: "referral",
      }),
    );
    // In Applied specifically. Anywhere else would be a candidate starting mid-pipeline.
    expect(await within(applied()).findByText("Nadia Rahman")).toBeInTheDocument();
  });

  /** Enter from a field, because she will be typing and talking, not aiming at a button. */
  it("submits on Enter without touching the button", async () => {
    const user = userEvent.setup();
    createMock.mockImplementation(() => new Promise(() => {}));

    renderBoard();
    await fillAndSubmit(user);
    await user.keyboard("{Enter}");

    await waitFor(() => expect(createMock).toHaveBeenCalledOnce());
  });

  it("puts a duplicate email against the email field and takes the card back", async () => {
    const user = userEvent.setup();
    createMock.mockRejectedValue(
      new ApiError(409, {
        type: "https://pipeline.example/problems/duplicate-email",
        title: "Already on the board",
        status: 409,
        detail: "Somebody with that email address is already in this pipeline.",
        field: "email",
      }),
    );

    renderBoard();
    await fillAndSubmit(user);
    await user.keyboard("{Enter}");

    // Against the field, not floating somewhere: the input is marked invalid and points
    // at the message describing it.
    const email = await screen.findByLabelText("Email");
    await waitFor(() => expect(email).toHaveAttribute("aria-invalid", "true"));
    expect(screen.getByRole("alert")).toHaveTextContent("already in this pipeline");
    expect(email).toHaveAccessibleDescription(/already in this pipeline/);

    // And the optimistic card is gone, rather than a row on the board for somebody who
    // was never saved.
    expect(within(applied()).queryByText("Nadia Rahman")).not.toBeInTheDocument();
    // Still open, still filled in — retyping four fields to fix one is the wrong answer.
    expect(screen.getByLabelText("Full name")).toHaveValue("Nadia Rahman");
  });

  /** Twenty writes a minute, and a recording is exactly when somebody clicks faster. */
  it("says plainly when the rate limiter refuses the write", async () => {
    const user = userEvent.setup();
    createMock.mockRejectedValue(
      new ApiError(429, {
        type: "https://pipeline.example/problems/rate-limited",
        title: "Too many requests",
        status: 429,
        detail: "Limit of 20 per window exceeded for write requests. Retry in 7s.",
      }),
    );

    renderBoard();
    await fillAndSubmit(user);
    await user.keyboard("{Enter}");

    expect(await screen.findByRole("alert")).toHaveTextContent(/more writes than the API allows/);
  });
});
