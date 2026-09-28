"use client";

import { useQuery } from "@tanstack/react-query";
import { getEvents } from "@/lib/client";
import type { Board, Candidate, Stage } from "@/lib/schemas";
import { Badge } from "@/components/ui/badge";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

const STAGE_LABEL: Record<Stage, string> = {
  APPLIED: "Applied",
  SCREENING: "Screening",
  INTERVIEW: "Interview",
  OFFER: "Offer",
  HIRED: "Hired",
  REJECTED: "Rejected",
};

/** "3 days ago" is what she reads; the exact instant is one hover away when it matters. */
function relative(iso: string, now = Date.now()): string {
  const seconds = Math.round((now - Date.parse(iso)) / 1000);
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["year", 31_536_000],
    ["month", 2_592_000],
    ["day", 86_400],
    ["hour", 3_600],
    ["minute", 60],
  ];
  const format = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) {
      return format.format(-Math.round(seconds / size), unit);
    }
  }
  return format.format(-seconds, "second");
}

export function CandidateDrawer({
  candidateId,
  board,
  onClose,
}: {
  candidateId: string | null;
  board: Board;
  onClose: () => void;
}) {
  // Read out of the board the recruiter is already looking at rather than fetched again:
  // a second request could answer differently and show her a drawer that disagrees with
  // the card she clicked.
  const candidate: Candidate | undefined = board.columns
    .flatMap((column) => column.candidates)
    .find((c) => c.id === candidateId);

  const timeline = useQuery({
    queryKey: ["events", candidateId],
    queryFn: () => getEvents(candidateId!),
    enabled: candidateId !== null,
  });

  return (
    // Base UI restores focus to whatever opened it on close and traps it while open, which
    // is the behaviour a hand-rolled drawer almost always gets wrong.
    <Sheet open={candidateId !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="flex w-full flex-col gap-0 overflow-y-auto sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{candidate?.fullName ?? "Candidate"}</SheetTitle>
          <SheetDescription>{candidate?.email}</SheetDescription>
        </SheetHeader>

        <div className="flex flex-col gap-6 px-4 pb-6">
          {candidate && (
            <div className="flex items-center gap-2">
              <Badge className="text-sm">{STAGE_LABEL[candidate.currentStage]}</Badge>
              <Tooltip>
                <TooltipTrigger render={<span className="rounded-md bg-muted px-2.5 py-1 text-sm font-medium" />}>
                  {candidate.timeInCurrentStageHumanised} in this stage
                </TooltipTrigger>
                <TooltipContent>Since {new Date(candidate.currentStageSince).toUTCString()}</TooltipContent>
              </Tooltip>
            </div>
          )}

          <section className="flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-medium">History</h3>
              <Tooltip>
                <TooltipTrigger render={<Badge variant="outline" className="cursor-default text-xs font-normal" />}>
                  append-only
                </TooltipTrigger>
                <TooltipContent className="max-w-xs">
                  Every move is a new entry. Nothing here can be edited or deleted — the database refuses
                  it, not just this screen.
                </TooltipContent>
              </Tooltip>
            </div>

            {timeline.isPending && <Skeleton className="h-24 w-full" />}

            {timeline.data && (
              <ol className="relative flex flex-col gap-4 border-l pl-5">
                {timeline.data.map((event, index) => (
                  <li key={`${event.occurredAt}-${index}`} className="relative">
                    <span
                      aria-hidden
                      className="absolute -left-[1.4rem] top-1.5 size-2.5 rounded-full border-2 border-background bg-foreground/70"
                    />
                    <div className="flex flex-col gap-0.5">
                      <span className="text-sm">
                        {event.fromStage ? (
                          <>
                            {STAGE_LABEL[event.fromStage]} <span aria-hidden>→</span>{" "}
                            <span className="font-medium">{STAGE_LABEL[event.toStage]}</span>
                          </>
                        ) : (
                          <span className="font-medium">Applied</span>
                        )}
                      </span>
                      <Tooltip>
                        <TooltipTrigger
                          render={
                            <time
                              dateTime={event.occurredAt}
                              className="w-fit cursor-default text-xs text-muted-foreground"
                            />
                          }
                        >
                          {relative(event.occurredAt)}
                        </TooltipTrigger>
                        <TooltipContent>{new Date(event.occurredAt).toUTCString()}</TooltipContent>
                      </Tooltip>
                      {event.reason && <span className="text-xs italic text-muted-foreground">{event.reason}</span>}
                      <span className="text-xs text-muted-foreground">by {event.actorName}</span>
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>
      </SheetContent>
    </Sheet>
  );
}
