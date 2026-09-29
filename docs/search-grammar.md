# The search box

Everything it accepts, and everything it will say when it cannot.

You can type a sentence or the query language; they end up at the same place. Whatever you
type, `GET /api/v1/search/explain?q=…` shows exactly how it was read — including what
"since Monday" resolved to — and the box shows the same thing as chips underneath it.

---

## Sentences

The normaliser is an ordered table of rewrites, not a model. A phrase it recognises is
rewritten; anything it does not recognise is left alone rather than reinterpreted.

| You type | It becomes |
|---|---|
| `Find Priya Sharma` | `name:"priya sharma"` |
| `Who's in Interview right now?` | `stage:interview` |
| `Who has been stuck in Screening for more than a week?` | `stage:screening in_stage_for:>7d` |
| `Who moved to Interview since Monday?` | `moved_to:interview since:monday` |
| `Who reached the Offer stage but didn't get hired?` | `reached:offer -status:hired` |
| `Everyone except rejected candidates` | `-status:rejected` |
| `sharam` | `sharam` (a bare term) |

The phrases each rule recognises:

| Meaning | Phrases |
|---|---|
| Currently in a stage | `in`, `at`, `stuck in`, `sitting in`, `waiting in`, `currently in`, `now in`, `still in` |
| Ever in a stage | `reached`, `got to`, `made it to`, `ever in`, `has been in`, `have been in` |
| A move into a stage | `moved to`, `moves to`, `moving to`, `went to`, `advanced to`, `progressed to`, and the `into` forms |
| Negation | `didn't`, `did not`, `never`, `wasn't`, `weren't`, `hasn't`, `haven't`, `not`, `except` |
| Time in stage | `for more than`, `for at least`, `for longer than`, `for over`, `for less than`, `for under`, `for at most` |
| When they applied | `applied more than … ago`, `applied in the last …`, `applied within the last …` |
| Date bounds | `since`, `after`, `from` · `before`, `until`, `up to`, `prior to` |

Filler is stripped only where it cannot be part of a name: `who`, `is`, `are`, `the`,
`show me`, `candidates`, `right now`, a trailing `?`. A capitalised word that is not a
stage, status or weekday is treated as a name.

---

## The language

```
query     := or
or        := and ( ("OR" | "or" | ",") and )*
and       := not ( ("AND" | "and" | WHITESPACE) not )*
not       := ("NOT" | "not" | "except" | "-") not | primary
primary   := "(" query ")" | predicate | bareTerm
predicate := field (":" | "=" | ">" | "<" | ">=" | "<=") value
value     := quotedString | word | duration | date | stageName
```

Whitespace means AND, so `stage:interview sharma` is both conditions. Quote a value that
contains a space: `name:"priya sharma"`.

### Fields

| Field | Matches | Comparisons | Examples |
|---|---|---|---|
| `stage` | Where they are now | `=` only | `stage:interview` |
| `reached` | Ever entered that stage, whatever happened after | `=` only | `reached:offer` |
| `moved_to` | An event into that stage | `=` only | `moved_to:interview` |
| `since` | Lower bound on a `moved_to` | `=` only | `moved_to:offer since:monday` |
| `before` | Upper bound on a `moved_to` | `=` only | `moved_to:offer before:today` |
| `in_stage_for` | How long they have been where they are | all | `in_stage_for:>7d` |
| `applied` | How long ago they applied | all | `applied:<30d` |
| `status` | `hired`, `rejected`, or `active` | `=` only | `status:active` |
| `name` | The name, fuzzily | `=` only | `name:sharma` |
| `name_like` | The name, forgiving a typo or two | `=` only | `name_like:pryia` |
| `source` | Where they came from, exactly | `=` only | `source:referral` |
| *(bare word)* | The name fuzzily, or the email exactly | — | `sharam` |

`since` and `before` mean nothing on their own — they modify the `moved_to` beside them,
and one with nothing to modify is an error rather than silently ignored.

Stages: `applied`, `screening`, `interview`, `offer`, `hired`, `rejected`.
Statuses: `hired`, `rejected`, `active`. `active` means "not in a terminal stage", derived
from the pipeline rather than listed separately.

### Comparisons

The operator goes after the colon: `in_stage_for:>7d`. It applies to **how old the thing
is**, not to its timestamp — so `>7d` is everyone who entered the stage more than seven
days ago, and `<7d` is everyone who arrived within the week.

| Written | Means |
|---|---|
| `in_stage_for:7d` | at least 7 days (a bare duration is a floor) |
| `applied:30d` | within the last 30 days (a bare duration here is a ceiling) |
| `>` `>=` | older than / at least that old |
| `<` `<=` | newer than / at most that old |

### Durations

`7d`, `2w`, `3mo`. Written out, `a week` becomes `7d`, `three days` becomes `3d`,
`more than` becomes `>`, `at least` becomes `>=`, `less than` becomes `<`.

Months are calendar months rather than thirty days, because "three months in Screening" is
a statement about the calendar. Days and weeks keep the time of day, so the answer does not
jump at midnight.

### Dates

`2025-03-11`, `today`, `yesterday`, `monday` … `sunday`, `last monday`, `this week`,
`last week`.

A weekday is the most recent one, today included — so "moved since Monday", asked on a
Monday, means this morning. `last monday` is then unambiguously the one before it. All of
it is UTC, and all of it resolves against the server's clock, which `/search/explain`
shows you: `since:monday` comes back with the instant it chose.

### Fuzzy matching

A name is matched with trigram word similarity at a threshold of 0.5, over the
accent-folded name — so `muller` finds `Zoë Müller`, and `sharam` finds `Priya Sharma`.

Ranking, best first:

```
score = 0.50 × nameMatch    exact 1.00 · prefix 0.85 · within an edit or two 0.80 · else word similarity
      + 0.20 × specificity  how many of your conditions the row satisfies
      + 0.20 × recency      decayed, half-life 30 days, from the last stage change
      + 0.10 × stagePriority further along the pipeline ranks higher
```

Weights are rescaled over the terms a query actually uses, so a query with no name in it
still scores across the whole range instead of topping out at 0.5. Every result carries a
`matchedOn` explaining itself: `["name ~ 'sharam' (0.80)", "stage = Interview"]`.

`name_like:` exists because trigrams cannot see a transposition — `pryia` against
`Priya Sharma` scores 0.333, below any threshold that would not also admit noise, while the
edit distance is 2. It is offered automatically when a search finds nobody.

### When nothing matches

A query that parses, runs and matches nobody comes back with alternatives, each a complete
query returning exactly the count it promises:

```
Nobody matches that.
  without status:hired                  31 results
  with a looser name match on "pryia"    7 results
```

---

## Limits

Checked before parsing, so a hostile query is cheap to refuse: 512 characters, 12
conditions, 5 levels of nesting.

---

## Errors

Every failure is RFC 9457 Problem Details with a machine-readable `code`, a sentence, and
`span` — a half-open `[start, end)` into the characters you typed, which is what the box
underlines. `didYouMean` is present where a correction could be worked out.

| Code | Example | Span | Says |
|---|---|---|---|
| `UNKNOWN_STAGE` | `stage:Intervew` | `[6,14]` | no stage called that · suggests `Interview` |
| `UNKNOWN_STATUS` | `status:hirred` | `[7,13]` | suggests `hired` |
| `UNKNOWN_FIELD` | `frobnicate:yes` | `[0,10]` | lists every valid field |
| `MISSING_VALUE` | `stage:` | `[0,6]` | `stage: needs a stage` · suggests each one |
| `MISSING_VALUE` | `moved_to:offer since:` | `[15,21]` | `since: needs a date` |
| `BAD_DURATION` | `in_stage_for:>banana` | `[14,20]` | gives the forms that work |
| `BAD_DATE` | `moved_to:offer since:banan` | `[21,26]` | gives the forms that work |
| `UNSUPPORTED_OPERATOR` | `stage:>interview` | `[0,16]` | a stage cannot be compared |
| `UNATTACHED_MODIFIER` | `since:monday` | `[0,12]` | `since:` needs something to modify |
| `UNCLOSED_GROUP` | `(stage:offer` | `[0,1]` | names where it opened |
| `UNCLOSED_QUOTE` | `name:"priya` | `[5,11]` | names where it opened |
| `UNEXPECTED_TOKEN` | `stage:offer )` | `[12,13]` | names the token |
| `EMPTY_QUERY` | `who is the?` | `[0,11]` | nothing in that to filter on |
| `QUERY_TOO_LONG` | 513 characters | `[512,513]` | the limit is 512 |
| `TOO_MANY_PREDICATES` | 13 conditions | at the 13th | narrow it down |
| `TOO_DEEPLY_NESTED` | 6 nested groups | at the 6th | the limit is 5 |
| `QUERY_TOO_COMPLEX` | — | `[0,0]` | a parser step budget, so a pathological input cannot hang |

Every row above was produced by running that query against the API, not transcribed from
the code.
