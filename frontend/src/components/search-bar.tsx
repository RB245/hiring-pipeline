"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ApiError, explain, suggest } from "@/lib/client";
import type { Explain, ExplainedNode, Span } from "@/lib/schemas";
import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

const DEBOUNCE_MS = 250;

const EXAMPLES = [
  "Who has been stuck in Screening for more than a week?",
  "Who moved to Interview since Monday?",
  "Who reached the Offer stage but didn't get hired?",
  "sharam",
];

/** Text broken into the pieces that get drawn differently. */
export type Segment = { text: string; kind: "plain" | "token" | "error" };

/**
 * Splits `text` at the given boundaries.
 *
 * <p>Every offset here came from the API — predicate positions from /explain, the invalid
 * range from the 422 body. Nothing is re-derived from the string, which matters because
 * the normaliser rewrites her sentence before parsing it: "for more than a week" becomes
 * `in_stage_for:>7d`, and no amount of client-side tokenising would find the characters
 * that correspond to a predicate that was never typed. The server knows; the client draws.
 */
export function segments(text: string, tokens: Span[], error?: Span): Segment[] {
  const marks = error ? [{ span: error, kind: "error" as const }] : tokens.map((span) => ({ span, kind: "token" as const }));
  const within = marks
    .filter(({ span }) => span[0] < span[1] && span[0] >= 0 && span[1] <= text.length)
    .sort((a, b) => a.span[0] - b.span[0]);

  const out: Segment[] = [];
  let at = 0;
  for (const { span, kind } of within) {
    if (span[0] < at) {
      continue; // Overlapping ranges: the first one wins rather than drawing twice.
    }
    if (span[0] > at) {
      out.push({ text: text.slice(at, span[0]), kind: "plain" });
    }
    out.push({ text: text.slice(span[0], span[1]), kind });
    at = span[1];
  }
  if (at < text.length) {
    out.push({ text: text.slice(at), kind: "plain" });
  }
  return out;
}

/** The source ranges of the conditions the parser actually found. */
function leafSpans(node: ExplainedNode): Span[] {
  if (node.children?.length) {
    return node.children.flatMap(leafSpans);
  }
  return node.source ? [node.source] : [];
}

/** "in_stage_for" reads as a column name; "In stage for" reads as English. */
function label(field: string): string {
  const words = field.replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function titleCase(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
}

/** One chip per condition, so she can see she was understood before trusting the results. */
type Interpretation = { text: string; means?: string };

function interpret(node: ExplainedNode, negated = false): Interpretation[] {
  if (node.type === "not") {
    return (node.children ?? []).flatMap((child) => interpret(child, !negated));
  }
  if (node.children?.length) {
    return node.children.flatMap((child) => interpret(child, negated));
  }
  const prefix = negated ? "Not " : "";
  if (node.type === "term") {
    return [{ text: `${prefix}Anyone matching "${node.value}"`, means: node.means }];
  }
  const comparison = node.operator ? ` ${node.operator} ` : " = ";
  return [
    {
      text: `${prefix}${label(node.field ?? "")}${comparison}${titleCase(node.value ?? "")}`,
      means: node.means,
    },
  ];
}

function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return settled;
}

/**
 * Controlled on the raw text, and it has to be: a relaxation suggestion under an empty
 * result set rewrites the box from outside, and a component holding its own text would
 * ignore that. What it does own is the debounce — one timer, feeding both the parse it
 * draws with and the query the parent runs, so the highlighting can never describe a
 * different string from the results.
 */
export function SearchBar({
  value,
  onChange,
  onCommit,
}: {
  value: string;
  onChange: (text: string) => void;
  onCommit: (query: string) => void;
}) {
  const text = value;
  const setText = onChange;
  const [completionsOpen, setCompletionsOpen] = useState(false);
  const [highlighted, setHighlighted] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const mirror = useRef<HTMLDivElement>(null);

  const settled = useDebounced(text, DEBOUNCE_MS);

  useEffect(() => {
    onCommit(settled.trim());
    // Keyed on the settled text alone. onCommit is the parent's setter and its identity
    // can change on any render; re-running then would re-announce a query she has not
    // retyped.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settled]);

  const parsed = useQuery({
    queryKey: ["explain", settled],
    queryFn: () => explain(settled),
    enabled: settled.trim().length > 0,
    retry: false,
  });

  const completions = useQuery({
    queryKey: ["suggest", text],
    queryFn: () => suggest(text),
    enabled: completionsOpen && text.length > 0,
    retry: false,
  });

  const failure = parsed.error instanceof ApiError ? parsed.error.problem : null;
  const explained: Explain | undefined = parsed.data;

  // Only highlight what belongs to the text on screen. While a request is in flight the
  // last answer describes a query she has since edited, and drawing those offsets would
  // underline the wrong characters — the one thing this feature must never do.
  const current = settled === text && !parsed.isFetching;
  const errorSpan = current ? failure?.span : undefined;
  const tokenSpans = current && explained ? leafSpans(explained.ast) : [];
  const chips = current && explained ? interpret(explained.ast) : [];

  const options = completions.data?.completions ?? [];

  function accept(index: number) {
    const completion = options[index];
    const replacing = completions.data?.replacing;
    if (!completion || !replacing) {
      return;
    }
    // Spliced at the range the API said it was completing. The token can span a space
    // inside quotes, so working out where the word began on this side would be guesswork.
    setText(`${text.slice(0, replacing[0])}${completion.value}${text.slice(replacing[1])}`);
    setCompletionsOpen(false);
    setHighlighted(0);
    input.current?.focus();
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (!completionsOpen || options.length === 0) {
      if (event.key === "ArrowDown") {
        setCompletionsOpen(true);
      }
      return;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setHighlighted((at) => (at + 1) % options.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setHighlighted((at) => (at - 1 + options.length) % options.length);
    } else if (event.key === "Enter") {
      event.preventDefault();
      accept(highlighted);
    } else if (event.key === "Escape") {
      setCompletionsOpen(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="relative">
        <div
          ref={mirror}
          aria-hidden
          className="pointer-events-none absolute inset-0 overflow-hidden whitespace-pre rounded-md border border-transparent px-3 py-2 font-mono text-sm leading-6"
        >
          {segments(text, tokenSpans, errorSpan).map((segment, index) => (
            <span
              key={index}
              data-kind={segment.kind}
              className={cn(
                segment.kind === "token" && "rounded-sm bg-sky-100 text-sky-900",
                segment.kind === "error" &&
                  "rounded-sm bg-red-100 text-red-900 underline decoration-red-500 decoration-wavy decoration-2 underline-offset-4",
              )}
            >
              {segment.text}
            </span>
          ))}
        </div>

        <input
          ref={input}
          value={text}
          onChange={(event) => {
            setText(event.target.value);
            setCompletionsOpen(true);
            setHighlighted(0);
          }}
          onKeyDown={onKeyDown}
          onBlur={() => setCompletionsOpen(false)}
          onScroll={(event) => {
            if (mirror.current) {
              mirror.current.scrollLeft = event.currentTarget.scrollLeft;
            }
          }}
          type="text"
          role="combobox"
          aria-expanded={completionsOpen && options.length > 0}
          aria-controls="search-completions"
          aria-autocomplete="list"
          aria-label="Search candidates"
          placeholder="Who has been stuck in Screening for more than a week?"
          spellCheck={false}
          autoComplete="off"
          className="relative w-full rounded-md border bg-transparent px-3 py-2 font-mono text-sm leading-6 text-transparent caret-foreground outline-none placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring"
        />

        {completionsOpen && options.length > 0 && (
          <ul
            id="search-completions"
            role="listbox"
            className="absolute z-20 mt-1 w-full overflow-hidden rounded-md border bg-popover shadow-md"
          >
            {options.map((completion, index) => (
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
        )}
      </div>

      {current && failure && (
        <div role="alert" className="flex flex-wrap items-center gap-2 text-sm">
          <span className="text-red-600">{failure.detail ?? failure.title}</span>
          {failure.didYouMean?.map((option) => (
            <button
              key={option}
              type="button"
              // Rewritten in place using the span the API underlined, so accepting a
              // correction cannot land in the wrong part of her sentence.
              onClick={() => {
                const span = failure.span;
                setText(span ? `${text.slice(0, span[0])}${option}${text.slice(span[1])}` : option);
                input.current?.focus();
              }}
              className="rounded-full border border-red-300 bg-red-50 px-2.5 py-0.5 text-xs font-medium text-red-700 hover:bg-red-100"
            >
              {option}
            </button>
          ))}
        </div>
      )}

      {chips.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-xs text-muted-foreground">Read as</span>
          {chips.map((chip, index) => (
            <Tooltip key={index}>
              <TooltipTrigger render={<Badge variant="secondary" className="cursor-default font-normal" />}>
                {chip.text}
              </TooltipTrigger>
              {chip.means && <TooltipContent>{chip.means}</TooltipContent>}
            </Tooltip>
          ))}
        </div>
      )}

      {text.length === 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-xs text-muted-foreground">Try</span>
          {EXAMPLES.map((example) => (
            <button
              key={example}
              type="button"
              onClick={() => setText(example)}
              className="rounded-full border px-2.5 py-0.5 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              {example}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
