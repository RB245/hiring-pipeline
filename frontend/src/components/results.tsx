"use client";

import { useQuery } from "@tanstack/react-query";
import { ApiError, getCandidates } from "@/lib/client";
import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

export function Results({
  query,
  onOpenCandidate,
  onUseQuery,
}: {
  query: string;
  onOpenCandidate: (id: string) => void;
  onUseQuery: (query: string) => void;
}) {
  const results = useQuery({
    queryKey: ["candidates", query],
    queryFn: () => getCandidates(query),
    retry: false,
  });

  // The search bar already shows the parse error, with the span underlined and the
  // corrections offered. Repeating it here would be two apologies for one mistake.
  if (results.error instanceof ApiError && results.error.status === 422) {
    return null;
  }

  const page = results.data;

  return (
    <div className="flex flex-col gap-3">
      {/*
        Announced, not just displayed. A sighted recruiter sees the count change as she
        types; without this a screen-reader user gets a silently rewritten list.
      */}
      <p role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {results.isPending
          ? "Searching…"
          : `${page?.candidates.length ?? 0} ${page?.candidates.length === 1 ? "result" : "results"}`}
      </p>

      {page && page.candidates.length === 0 && (
        <div className="flex flex-col gap-2 rounded-lg border border-dashed p-4">
          <p className="text-sm">Nobody matches that.</p>
          {page.suggestions && page.suggestions.length > 0 ? (
            <>
              <p className="text-xs text-muted-foreground">Try one of these instead:</p>
              <div className="flex flex-wrap gap-2">
                {page.suggestions.map((suggestion) => (
                  // Each one carries a complete query that returns exactly the count it
                  // promises, so acting on it is putting that string in the box.
                  <button
                    key={suggestion.query}
                    type="button"
                    onClick={() => onUseQuery(suggestion.query)}
                    className="rounded-full border bg-background px-3 py-1 text-xs hover:bg-accent"
                  >
                    {suggestion.suggestion}
                    <span className="ml-1.5 text-muted-foreground">
                      {suggestion.results} {suggestion.results === 1 ? "result" : "results"}
                    </span>
                  </button>
                ))}
              </div>
            </>
          ) : (
            <p className="text-xs text-muted-foreground">
              There is nothing to loosen here — every part of that query is already as broad as it gets.
            </p>
          )}
        </div>
      )}

      <ul className="flex flex-col gap-2">
        {page?.candidates.map((candidate) => (
          <li key={candidate.id}>
            <Tooltip>
              <TooltipTrigger
                render={
                  <button
                    type="button"
                    onClick={() => onOpenCandidate(candidate.id)}
                    className="flex w-full items-center justify-between gap-4 rounded-md border bg-background p-3 text-left outline-none hover:bg-accent/40 focus-visible:ring-2 focus-visible:ring-ring"
                  />
                }
              >
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium">{candidate.fullName}</span>
                    <span className="block truncate text-xs text-muted-foreground">{candidate.email}</span>
                  </span>
                  <span className="flex shrink-0 items-center gap-2">
                    <span className="text-xs text-muted-foreground">
                      {candidate.timeInCurrentStageHumanised} in stage
                    </span>
                    <Badge variant="secondary">{candidate.currentStage.toLowerCase()}</Badge>
                    {candidate.score !== undefined && (
                      <Badge variant="outline" className="font-mono text-xs">
                        {candidate.score.toFixed(2)}
                      </Badge>
                    )}
                  </span>
              </TooltipTrigger>
              {/* Why this row is here at all, which a ranked list otherwise never says. */}
              {candidate.matchedOn && candidate.matchedOn.length > 0 && (
                <TooltipContent className="max-w-sm">
                  <span className="block text-xs font-medium">Matched on</span>
                  <ul className="mt-1 list-inside list-disc">
                    {candidate.matchedOn.map((reason) => (
                      <li key={reason} className="font-mono text-xs">
                        {reason}
                      </li>
                    ))}
                  </ul>
                </TooltipContent>
              )}
            </Tooltip>
          </li>
        ))}
      </ul>
    </div>
  );
}
