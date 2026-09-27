- 07 — Search, part one: parsing

- How I want you to work (same rules as file 01)

Think before coding: state assumptions, ask when uncertain, show me the options instead of silently picking
one, push back if there's a simpler way, stop and say so if something is unclear.
Simplicity first: minimum code that solves it, nothing speculative, no abstractions for single-use code.
Surgical: touch only what you must, match existing style, don't refactor what isn't broken, only clean up
orphans your own change created.
Goal-driven: give me a numbered plan where each step names how it gets verified, then loop until those
checks pass.

- The decision, up front

No LLM anywhere in the query path. I know it's the tempting answer. It isn't reproducible across identical
inputs, I can't point an error message at a specific character in her input, I'd be paying latency and money
on every keystroke, and I can't unit test it. The hard requirement here is telling her precisely why a query
is invalid, and a model will invent an interpretation rather than reject something. So this is a hand-written
parser. If you still disagree after that, say so now rather than halfway through.

- The pipeline

 
Normalizer   natural language phrases into canonical DSL, via a static ordered rewrite table
Lexer        tokens, every one carrying a source span [start, end]
Parser       recursive descent, produces an AST
Validator    semantics: does that stage exist, does the duration parse, resolve relative dates
 

Every token and every AST node carries its source span. That's what lets error messages point at exact
characters and lets the UI underline the right part of the input. Don't skip it, retrofitting spans later is
miserable.

Nothing in this file touches SQL. That's file 08.

- Grammar

 
query     := or
or        := and ( ("OR" | "or" | ",") and )*
and       := not ( ("AND" | "and" | WHITESPACE) not )*
not       := ("NOT" | "not" | "except" | "-") not | primary
primary   := "(" query ")" | predicate | bareTerm
predicate := field (":" | "=" | ">" | "<" | ">=" | "<=") value
value     := quotedString | word | duration | date | stageName
 

Whitespace means AND, so `stage:interview sharma` is an implicit conjunction.

- Fields

Each field is a `FieldHandler` implementation registered into a `Map<String, FieldHandler>` by Spring. Adding
a new searchable field must not require editing the lexer, parser, validator or service. I'll test exactly
that in file 10, so build it that way now.

 
stage:          current stage equals, e.g. stage:interview
reached:        ever entered that stage
moved_to:       has an event with that to_stage
since: before:  time modifiers that attach to moved_to
in_stage_for:   compares now() minus current_stage_since, e.g. in_stage_for:>7d
status:         one of rejected, hired, active
name:           fuzzy, e.g. name:"priya sharma"
applied:        on created_at, e.g. applied:<30d
bare word       no field given, searches name and email fuzzily
 

Durations take `7d`, `2w`, `3mo` and also words, so "a week" becomes `7d`, "more than" becomes `>`, "at
least" becomes `>=`. Dates take ISO plus `today`, `yesterday`, `monday`, `last monday`, `this week`.
Everything relative resolves against the injected Clock, never the system clock.

- Acceptance criteria

These eight inputs must normalise exactly like this:

 
Find Priya Sharma                                     ->  name:"priya sharma"
sharam                                                ->  sharam   (bare term)
Who's in Interview right now?                         ->  stage:interview
Who has been stuck in Screening for more than a week? ->  stage:screening in_stage_for:>7d
Who moved to Interview since Monday?                  ->  moved_to:interview since:monday
Who reached the Offer stage but didn't get hired?     ->  reached:offer -status:hired
Everyone except rejected candidates                   ->  -status:rejected
stage:interview in_stage_for:>3d -status:rejected     ->  unchanged, it's already DSL
 

Strip filler words (who, is, are, the, "right now", candidates, the question mark) only where it's
unambiguous. Don't get clever and start stripping things that could be part of a name.

- Errors

She should never get a bare empty result and a bad query must say what's wrong. Problem Details with a
machine-readable code, a human message, the source span, and suggestions where we have them:

 
stage:Intervew            UNKNOWN_STAGE, span [6,14], didYouMean ["Interview"] via levenshtein
in_stage_for:>banana      BAD_DURATION, message gives examples
stage:                    MISSING_VALUE, suggests stage:screening
moved_to:x since:         MISSING_VALUE, "since: needs a date"
(stage:offer              UNCLOSED_GROUP, names the position
frobnicate:yes            UNKNOWN_FIELD, lists valid fields, didYouMean if close
 

- Abuse guards

A query language is an attack surface. Before parsing, reject anything over 512 characters, more than 12
predicates, or nested deeper than 5, with a 422. Put a hard timeout on the parse. It must never hang.

- Done when

A golden-file test of roughly 60 queries maps input to expected normalised DSL and AST, including all eight
acceptance rows verbatim. Every error case above has a test asserting both the message and the span, because
the span is the part that's easy to get subtly wrong. No SQL exists yet.
