# ADR 0002 — A hand-written parser, not a language model

Status: accepted

## Context

The search box accepts sentences: "Who has been stuck in Screening for more than a week?"
Handing that to a language model is the obvious move in 2026, and it would have taken an
afternoon.

The hard requirement is not understanding the sentence. It is telling the recruiter
precisely why a query is invalid — which characters are wrong, and what to type instead.

## Decision

A deterministic pipeline, with no model anywhere in the query path:

```
Normalizer  ordered rewrite table, sentence → canonical DSL
Lexer       tokens, each carrying a source span
QueryGuards 512 characters, 12 predicates, 5 levels of nesting
Parser      recursive descent → AST
Validator   semantics: does that stage exist, does that duration parse
```

Every token and every AST node carries two spans: one into the normalised DSL, one into
the characters she typed. The normaliser rewrites her sentence before it is parsed, so
those are different coordinate systems and both are needed.

## Consequences

An error can point at a character range, and the UI underlines it without re-deriving
anything. `stage:Intervew` comes back as `UNKNOWN_STAGE`, span `[6,14]`, `didYouMean:
["Interview"]`. A model cannot do that, because it has no stable notion of where in the
input it went wrong.

The same input always parses the same way, so a golden file of sixty queries is a
meaningful test. There is no latency and no per-keystroke cost.

A model would have invented an interpretation rather than rejecting bad input, which is
exactly the failure mode this feature cannot have: a recruiter acting on a list that
quietly means something other than what she asked for.

The cost is that the language is only as broad as the rewrite table. "Who did we speak to
last week that we liked" parses as a name search and finds nobody, where a model would
have guessed. That is the trade: the system is narrower and never wrong about what it
understood, and `/search/explain` exists so she can see which it was.
