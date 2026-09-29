"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, createCandidate, getBoard, transition, type NewCandidate } from "@/lib/client";
import type { Board as BoardData, Candidate, Stage } from "@/lib/schemas";
import { AddCandidateDialog } from "@/components/add-candidate-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

const STAGE_LABEL: Record<Stage, string> = {
  APPLIED: "Applied",
  SCREENING: "Screening",
  INTERVIEW: "Interview",
  OFFER: "Offer",
  HIRED: "Hired",
  REJECTED: "Rejected",
};

/**
 * Moving a card between columns without waiting for the round trip, and putting it back
 * when the backend says it was looking at a stale board.
 */
function moveCard(board: BoardData, candidateId: string, to: Stage): BoardData {
  const moving = board.columns.flatMap((column) => column.candidates).find((c) => c.id === candidateId);
  if (!moving) {
    return board;
  }
  const moved: Candidate = { ...moving, currentStage: to, legalTargets: [] };
  return {
    columns: board.columns.map((column) => {
      const without = column.candidates.filter((c) => c.id !== candidateId);
      const candidates = column.stage === to ? [moved, ...without] : without;
      return { ...column, candidates, count: candidates.length };
    }),
  };
}

/**
 * A card for somebody the server has not acknowledged yet. The id is temporary and is
 * replaced when the real row arrives; nothing can be done to them in the meantime, which
 * is why the placeholder carries no legal targets.
 */
function withNewCandidate(board: BoardData, candidate: NewCandidate, temporaryId: string): BoardData {
  const placeholder: Candidate = {
    id: temporaryId,
    fullName: candidate.fullName,
    email: candidate.email,
    phone: candidate.phone ?? null,
    source: candidate.source ?? null,
    currentStage: "APPLIED",
    currentStageSince: new Date().toISOString(),
    timeInCurrentStage: "PT0S",
    timeInCurrentStageHumanised: "just now",
    createdAt: new Date().toISOString(),
    legalTargets: [],
  };
  return {
    columns: board.columns.map((column) =>
      column.stage === "APPLIED"
        ? { ...column, candidates: [placeholder, ...column.candidates], count: column.count + 1 }
        : column,
    ),
  };
}

export function BoardView({
  initialBoard,
  onOpenCandidate,
}: {
  initialBoard: BoardData;
  onOpenCandidate: (id: string) => void;
}) {
  const queries = useQueryClient();
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState<Set<string>>(new Set());
  const [adding, setAdding] = useState(false);
  // Held here rather than inside the dialog: the optimistic insert unwinds on failure and
  // would take a message living in the dialog's own state with it.
  const [addFailure, setAddFailure] = useState<ApiError | null>(null);
  const grid = useRef<HTMLDivElement>(null);

  const board = useQuery({ queryKey: ["board"], queryFn: getBoard, initialData: initialBoard });

  const move = useMutation({
    mutationFn: ({ candidate, to }: { candidate: Candidate; to: Stage }) =>
      transition(candidate.id, candidate.currentStage, to),

    onMutate: async ({ candidate, to }) => {
      // Any board refetch in flight would land after the optimistic move and undo it.
      await queries.cancelQueries({ queryKey: ["board"] });
      const previous = queries.getQueryData<BoardData>(["board"]);
      queries.setQueryData<BoardData>(["board"], (current) =>
        current ? moveCard(current, candidate.id, to) : current,
      );
      setPending((ids) => new Set(ids).add(candidate.id));
      setNotice(null);
      return { previous };
    },

    onError: (error, { candidate }, context) => {
      // Put the board back exactly as it was, then say so. A silent revert looks like the
      // click did not register, and she clicks again.
      if (context?.previous) {
        queries.setQueryData(["board"], context.previous);
      }
      const problem = error instanceof ApiError ? error.problem : null;
      if (problem?.actualCurrentStage) {
        setNotice(
          `${candidate.fullName} had already moved to ${STAGE_LABEL[problem.actualCurrentStage]}. ` +
            `The board has been put back and refreshed.`,
        );
      } else {
        setNotice(`${candidate.fullName} could not be moved: ${problem?.detail ?? "the request failed"}.`);
      }
    },

    onSettled: (_data, _error, { candidate }) => {
      setPending((ids) => {
        const next = new Set(ids);
        next.delete(candidate.id);
        return next;
      });
      // Whether it worked or not, the server is the authority on where everyone is now.
      queries.invalidateQueries({ queryKey: ["board"] });
    },
  });

  const add = useMutation({
    mutationFn: (candidate: NewCandidate) => createCandidate(candidate),

    onMutate: async (candidate) => {
      await queries.cancelQueries({ queryKey: ["board"] });
      const previous = queries.getQueryData<BoardData>(["board"]);
      const temporaryId = `pending-${Date.now()}`;
      queries.setQueryData<BoardData>(["board"], (current) =>
        current ? withNewCandidate(current, candidate, temporaryId) : current,
      );
      setAddFailure(null);
      return { previous };
    },

    onError: (error, _candidate, context) => {
      // The same rollback the move buttons do: put the board back, then say why. The
      // dialog stays open with what she typed still in it, because retyping four fields
      // to fix one of them is the worst possible answer to a duplicate email.
      if (context?.previous) {
        queries.setQueryData(["board"], context.previous);
      }
      setAddFailure(error instanceof ApiError ? error : null);
    },

    onSuccess: () => {
      setAdding(false);
      setAddFailure(null);
    },

    onSettled: () => queries.invalidateQueries({ queryKey: ["board"] }),
  });

  /**
   * Arrow keys between cards, so the board is usable without a mouse rather than merely
   * reachable by one. Left and right cross columns at the same depth, which is how the
   * board reads: the columns are the pipeline, and moving across it is the point.
   */
  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    const keys = ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"];
    if (!keys.includes(event.key)) {
      return;
    }
    const focused = (event.target as HTMLElement).closest<HTMLElement>("[data-card]");
    if (!focused) {
      return;
    }
    event.preventDefault();
    const column = Number(focused.dataset.column);
    const row = Number(focused.dataset.row);
    const step = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[event.key]!;

    for (let target = [column + step[0], row + step[1]]; ; target = [target[0] + step[0], target[1] + step[1]]) {
      if (target[0] < 0 || target[0] >= board.data.columns.length) {
        return;
      }
      const next = grid.current?.querySelector<HTMLElement>(
        `[data-card][data-column="${target[0]}"][data-row="${Math.max(0, target[1])}"]`,
      );
      if (next) {
        next.focus();
        return;
      }
      // Empty column, or past the end of a short one: keep going sideways rather than
      // swallowing the key, so a gap in the board is not a dead end.
      if (step[1] !== 0) {
        return;
      }
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {notice && (
        <div
          role="status"
          aria-live="assertive"
          className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900"
        >
          {notice}
        </div>
      )}

      <div className="flex justify-end">
        <Button size="sm" onClick={() => setAdding(true)}>
          Add candidate
        </Button>
      </div>

      <AddCandidateDialog
        open={adding}
        onOpenChange={(open) => {
          setAdding(open);
          if (!open) {
            setAddFailure(null);
          }
        }}
        onSubmit={(candidate) => add.mutate(candidate)}
        pending={add.isPending}
        failure={addFailure}
      />

      <div
        ref={grid}
        onKeyDown={onKeyDown}
        className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6"
      >
        {board.data.columns.map((column, columnIndex) => (
          <section
            key={column.stage}
            aria-label={`${STAGE_LABEL[column.stage]}, ${column.count} candidates`}
            className="flex min-h-40 flex-col gap-2 rounded-lg bg-muted/40 p-2"
          >
            <header className="flex items-center justify-between px-1">
              <h2 className="text-sm font-medium">{STAGE_LABEL[column.stage]}</h2>
              <Badge variant="secondary" aria-hidden>
                {column.count}
              </Badge>
            </header>
            <ul className="flex flex-col gap-2">
              {column.candidates.map((candidate, rowIndex) => (
                <li key={candidate.id}>
                  <CandidateCard
                    candidate={candidate}
                    column={columnIndex}
                    row={rowIndex}
                    busy={pending.has(candidate.id)}
                    onOpen={() => onOpenCandidate(candidate.id)}
                    onMove={(to) => move.mutate({ candidate, to })}
                  />
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  );
}

function CandidateCard({
  candidate,
  column,
  row,
  busy,
  onOpen,
  onMove,
}: {
  candidate: Candidate;
  column: number;
  row: number;
  busy: boolean;
  onOpen: () => void;
  onMove: (to: Stage) => void;
}) {
  const terminal = candidate.legalTargets.length === 0;

  return (
    <div className={cn("rounded-md border bg-background p-2 shadow-sm", busy && "opacity-60")}>
      <button
        type="button"
        data-card
        data-column={column}
        data-row={row}
        onClick={onOpen}
        className="w-full rounded text-left outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <span className="block truncate text-sm font-medium">{candidate.fullName}</span>
        <span className="block text-xs text-muted-foreground">
          {candidate.timeInCurrentStageHumanised} in stage
        </span>
      </button>

      <div className="mt-2 flex gap-1">
        {terminal ? (
          // Disabled and explained, rather than hidden. A recruiter who cannot see why a
          // button is missing assumes the app is broken; one who is told "Rejected is
          // terminal" has learnt the pipeline.
          <Tooltip>
            <TooltipTrigger
              render={<span tabIndex={0} className="rounded outline-none focus-visible:ring-2 focus-visible:ring-ring" />}
            >
              <Button size="sm" variant="outline" disabled className="pointer-events-none h-7 text-xs">
                No moves
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              {STAGE_LABEL[candidate.currentStage]} is a terminal stage — this candidate&apos;s history is
              complete and cannot be moved on.
            </TooltipContent>
          </Tooltip>
        ) : (
          // One button per move the API says exists, so the board cannot offer a move the
          // backend would refuse.
          candidate.legalTargets.map((target) => (
            <Button
              key={target}
              size="sm"
              variant={target === "REJECTED" ? "outline" : "default"}
              disabled={busy}
              onClick={() => onMove(target)}
              className="h-7 text-xs"
            >
              {target === "REJECTED" ? "Reject" : `→ ${STAGE_LABEL[target]}`}
            </Button>
          ))
        )}
      </div>
    </div>
  );
}
