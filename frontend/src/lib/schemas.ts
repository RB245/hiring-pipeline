import { z } from "zod";

/**
 * Every shape the API can hand us, parsed rather than asserted.
 *
 * These are the only place the frontend is allowed to believe something about the
 * backend's JSON. A `as CandidateResponse` would compile just as happily against a
 * response that changed last week; `parse` fails loudly at the point of entry, which is
 * the one place the failure is still cheap to understand.
 *
 * Spans and `legalTargets` matter more than most: the search box underlines whatever span
 * it is given and the board renders a button per legal target, so both are read straight
 * from here and never re-derived.
 */

export const Stage = z.enum([
  "APPLIED",
  "SCREENING",
  "INTERVIEW",
  "OFFER",
  "HIRED",
  "REJECTED",
]);
export type Stage = z.infer<typeof Stage>;

/** [start, end) into the text, half-open, exactly as the API means it. */
export const Span = z.tuple([z.number().int(), z.number().int()]);
export type Span = z.infer<typeof Span>;

export const Candidate = z.object({
  id: z.string(),
  fullName: z.string(),
  email: z.string(),
  phone: z.string().nullish(),
  source: z.string().nullish(),
  currentStage: Stage,
  currentStageSince: z.string(),
  timeInCurrentStage: z.string(),
  timeInCurrentStageHumanised: z.string(),
  createdAt: z.string(),
  /** The moves this candidate has. Empty means terminal, which is why it is not optional. */
  legalTargets: z.array(Stage),
  /** Search only. */
  score: z.number().optional(),
  matchedOn: z.array(z.string()).optional(),
});
export type Candidate = z.infer<typeof Candidate>;

export const BoardColumn = z.object({
  stage: Stage,
  count: z.number().int(),
  candidates: z.array(Candidate),
});
export type BoardColumn = z.infer<typeof BoardColumn>;

export const Board = z.object({ columns: z.array(BoardColumn) });
export type Board = z.infer<typeof Board>;

export const Suggestion = z.object({
  /** The phrase to show her. */
  suggestion: z.string(),
  /** A complete query that returns exactly `results` candidates. Put it in the box. */
  query: z.string(),
  results: z.number().int(),
});
export type Suggestion = z.infer<typeof Suggestion>;

export const CandidatePage = z.object({
  candidates: z.array(Candidate),
  nextCursor: z.string().nullish(),
  /** Search only: how the query was read. */
  query: z.string().optional(),
  /** Search only, and only when it found nobody. */
  suggestions: z.array(Suggestion).optional(),
});
export type CandidatePage = z.infer<typeof CandidatePage>;

export const StageEvent = z.object({
  candidateId: z.string(),
  fromStage: Stage.nullish(),
  toStage: Stage,
  eventType: z.enum(["APPLIED", "ADVANCED", "REJECTED", "HIRED"]),
  occurredAt: z.string(),
  actorId: z.string(),
  actorName: z.string(),
  reason: z.string().nullish(),
});
export type StageEvent = z.infer<typeof StageEvent>;

/**
 * The parse tree. Recursive, so the type is written out and the schema uses a getter —
 * `children` is absent on a leaf rather than empty, which is how the API distinguishes a
 * predicate from a group of one.
 */
export type ExplainedNode = {
  type: string;
  field?: string;
  operator?: string;
  value?: string;
  means?: string;
  span?: Span;
  source?: Span;
  children?: ExplainedNode[];
};

export const ExplainedNode: z.ZodType<ExplainedNode> = z.lazy(() =>
  z.object({
    type: z.string(),
    field: z.string().optional(),
    operator: z.string().optional(),
    value: z.string().optional(),
    means: z.string().optional(),
    span: Span.optional(),
    source: Span.optional(),
    children: z.array(ExplainedNode).optional(),
  }),
);

export const Explain = z.object({
  query: z.string(),
  dsl: z.string(),
  ast: ExplainedNode,
});
export type Explain = z.infer<typeof Explain>;

export const Completion = z.object({
  value: z.string(),
  label: z.string(),
  kind: z.enum(["FIELD", "VALUE"]),
});
export type Completion = z.infer<typeof Completion>;

export const Suggest = z.object({
  /** The half-written token the completions stand in for. */
  replacing: Span,
  completions: z.array(Completion),
});
export type Suggest = z.infer<typeof Suggest>;

/**
 * RFC 9457. `span` indexes the raw query she typed, which is the text the input is
 * showing — the reason the box never has to work out where the error was.
 */
export const Problem = z.object({
  type: z.string(),
  title: z.string(),
  status: z.number().int(),
  detail: z.string().nullish(),
  correlationId: z.string().nullish(),
  /** Search failures only. */
  code: z.string().optional(),
  span: Span.optional(),
  didYouMean: z.array(z.string()).optional(),
  /** Duplicate-email failures: which field the message belongs against. */
  field: z.string().optional(),
  /** Illegal-transition failures only. */
  legalTargets: z.array(Stage).optional(),
  /** Stale-view failures only: what this tab believed, and what is actually true. */
  expectedCurrentStage: Stage.optional(),
  actualCurrentStage: Stage.optional(),
});
export type Problem = z.infer<typeof Problem>;
