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

/**
 * Everything the browser fetches, parsed on the way in.
 *
 * <p>Runs in the browser and hits the BFF, never Spring. The schemas are shared with the
 * server path, so a response is validated by the same rules whichever side read it.
 */

/** A failure the UI can act on: the Problem Details body, kept whole. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem | null,
  ) {
    super(problem?.detail ?? problem?.title ?? `Request failed with ${status}`);
    this.name = "ApiError";
  }
}

async function call<T>(path: string, schema: z.ZodType<T>, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { Accept: "application/json", ...init?.headers },
  });

  if (!response.ok) {
    // A body that is not Problem Details is still a failure; it just cannot explain
    // itself, and pretending otherwise would swallow the status.
    const parsed = Problem.safeParse(await response.json().catch(() => null));
    throw new ApiError(response.status, parsed.success ? parsed.data : null);
  }
  return schema.parse(await response.json());
}

export function getBoard(): Promise<Board> {
  return call("/api/v1/pipeline", Board);
}

export function getCandidates(query: string, limit = 50): Promise<CandidatePage> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (query.trim()) {
    params.set("q", query);
  }
  return call(`/api/v1/candidates?${params}`, CandidatePage);
}

export function getEvents(candidateId: string): Promise<StageEvent[]> {
  return call(`/api/v1/candidates/${candidateId}/events`, z.array(StageEvent));
}

export function explain(query: string): Promise<Explain> {
  return call(`/api/v1/search/explain?q=${encodeURIComponent(query)}`, Explain);
}

export function suggest(query: string): Promise<Suggest> {
  return call(`/api/v1/search/suggest?q=${encodeURIComponent(query)}`, Suggest);
}

/**
 * `expectedCurrentStage` is what makes an optimistic board safe: if the candidate moved
 * while this tab was looking at a stale card, the backend refuses with 409 rather than
 * applying the move to a position that no longer exists, and the optimistic update rolls
 * back. Sending the stage we think they are in is the whole mechanism.
 */
export function transition(
  candidateId: string,
  expectedCurrentStage: Stage,
  toStage: Stage,
): Promise<StageEvent> {
  return call(`/api/v1/candidates/${candidateId}/transitions`, StageEvent, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ expectedCurrentStage, toStage }),
  });
}
