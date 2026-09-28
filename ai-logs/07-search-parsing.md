# 07 - Search part one: normalizer, lexer, parser, validator

Prompt given: `prompts/07-*.md`

---

<!-- I'll read the repo first — the existing conventions matter more than anything I'd invent.

  Read 13 files, listed 1 directory, ran 5 shell commands

Read the repo: the existing conventions (ports in application, ArchUnit guarding domain, Problems/ProblemDetails as the single error mould, TransitionRules as the composed-from-a-list precedent for the FieldHandler map) are clear enough to build against. Gradle runs, Docker is up — though nothing in this file needs a container.

On the LLM

I agree, no argument. The span requirement alone settles it: you cannot ask a model for "characters 6–14 of the input" and get the same answer twice. The only place I'd ever put a model near this is offline — mining logged queries that failed to parse into candidate rows for the rewrite table, reviewed by a human before they land. That's a table edit, not the query path.

Readings of the spec I've committed to

These are the non-obvious calls. Say if any is wrong.

1. in_stage_for:>7d — the comparator belongs to the predicate, not the value. Your grammar makes > an operator between field and value, but every acceptance row writes it after the colon. I'll fold them: when the operator is : and the value starts with a comparator, that comparator becomes the predicate's operator. So in_stage_for:>7d and in_stage_for>7d produce an identical AST, and canonical rendering always emits the :> form your rows use.
2. Comparison operators on a duration compare the age, not the timestamp. in_stage_for:>7d = sitting there longer than a week; applied:<30d = applied within the last 30 days. Easy to invert silently, so it gets its own test in both directions.
3. since:/before: bind to moved_to: within the enclosing AND group, in the validator, over the AST. A since: with no moved_to: to attach to is an error (UNATTACHED_MODIFIER), not a silent no-op — silently dropping a filter returns wrong rows, which is the failure mode you've been designing against all project. That's one code beyond your catalogue; tell me if you'd rather it parse and be ignored.
4. Rewrites emit canonical lowercase; text she typed is preserved verbatim. Find Priya Sharma → name:"priya sharma" because the rule lowercases what it captured. stage:Intervew keeps its capital I, so the span and the echoed DSL stay honest about what she typed.
5. Existing DSL tokens are masked before the rewrite table runs. Acceptance row 8 (stage:interview in_stage_for:>3d -status:rejected unchanged) is fragile otherwise — the bare-status rule would turn -status:rejected into -status:status:rejected. Masking field:value spans and quoted strings first is more robust than a pile of negative lookbehinds.
6. Bare - is NOT only at a token start (after start-of-input, whitespace or (); elsewhere it's part of a word, so jean-luc survives.
7. Guards run on the token stream, before the parser. Length on her raw input (that's the attack surface), then normalize, lex, count predicates and paren depth, then parse. All three checks are genuinely pre-parse.
8. The typed exception lands here; the Problem Details handler lands here too but is only exercised in file 08, since there's no endpoint yet. The exception carries code, message, span, suggestions — everything the envelope needs — so 08 adds no new fields.

Two pushbacks

The Map<String, FieldHandler> injected by Spring is keyed by bean name, not field name. Making that work means @Component("in_stage_for") on every handler — the routing key becomes an annotation string nobody looks at, and a typo there fails at query time, not startup. I want a FieldRegistry that takes List<FieldHandler> and builds the map from handler.field(), failing fast on a duplicate. Same injection point for you, same one-file-plus-registration story for file 10, key can't drift from the thing it names.

A wall-clock timeout on the parse is machinery for an impossible scenario. Recursive descent over a ≤512-char input, with depth capped at 5 and every production consuming at least one token, is O(n) with no loop that can fail to advance. A real timeout needs a second thread, and it makes the failure non-deterministic — which means a flaky test, and a flaky test on a guard is worse than no guard. I'd rather spend a deterministic step budget: a counter incremented per token consumed and per recursion, aborting past a fixed ceiling. Same "it can never hang" guarantee, no thread, and it's assertable in a unit test. Plus a fuzz test that a few thousand random and adversarial inputs all terminate.

The one thing I want you to pick

Spans are the load-bearing part of this file, and the normalizer is where they get hard: once Who has been stuck in Screening for more than a week? becomes stage:screening in_stage_for:>7d, a span into the DSL no longer points at anything she typed. Let me ask before I build the data model around it.

● User answered Claude's questions:
· Once the normalizer has rewritten her sentence into DSL, what should a span point at? → Dual spans, coarse on rewrites (Recommended)
· The spec asks for a hard timeout on the parse. Which guarantee do you want? → Deterministic step budget (Recommended)

Both recommendations taken. Here's the plan, then I build.

┌─────┬────────────────────────────────────────┬──────────────────────────────────────────────────────────┐
│  #  │                  Step                  │                       Verified by                        │
├─────┼────────────────────────────────────────┼──────────────────────────────────────────────────────────┤
│     │                                        │ Token-level test asserting exact [start,end) for         │
│ 1   │ Span, tokens, Lexer — every token      │ stage:interview in_stage_for:>3d -status:rejected; cases │
│     │ carries a DSL span and a source span   │  for quoted values, >= maximal munch, and jean-luc       │
│     │                                        │ keeping its hyphen                                       │
├─────┼────────────────────────────────────────┼──────────────────────────────────────────────────────────┤
│     │ Normalizer: mask existing DSL/quoted   │ All eight acceptance rows exactly; a test that row 8 is  │
│ 2   │ regions, then an ordered (pattern,     │ byte-identical through the normalizer; a test that       │
│     │ emitter) table, emitting a segment map │ sourceSpan maps verbatim text character-exact and        │
│     │                                        │ rewritten phrases to the whole phrase                    │
├─────┼────────────────────────────────────────┼──────────────────────────────────────────────────────────┤
│ 3   │ AST + recursive-descent Parser +       │ render(parse(x)) equals the expected DSL for all 60      │
│     │ canonical Dsl renderer                 │ golden rows; precedence tests for , vs whitespace vs OR  │
├─────┼────────────────────────────────────────┼──────────────────────────────────────────────────────────┤
│ 4   │ Guards: 512 chars, ≤12 predicates,     │ A test at each boundary (12 passes, 13 fails) and a fuzz │
│     │ depth ≤5, step budget                  │  test that ~5k random/adversarial inputs all terminate   │
├─────┼────────────────────────────────────────┼──────────────────────────────────────────────────────────┤
│     │                                        │ Registry test: every registered field round-trips,       │
│ 5   │ FieldHandler + FieldRegistry + nine    │ duplicate registration fails at startup; ArchUnit rule   │
│     │ handlers + sealed ResolvedValue        │ that lexer/parser/normalizer/validator do not depend on  │
│     │                                        │ the fields package                                       │
├─────┼────────────────────────────────────────┼──────────────────────────────────────────────────────────┤
│     │ Validator: stage/status/duration/date  │ Fixed-clock tests for today, yesterday, monday, last     │
│ 6   │ resolution against the injected Clock, │ monday, this week, ISO; both directions of the age       │
│     │  since/before attachment               │ comparison; attachment and the unattached-modifier error │
├─────┼────────────────────────────────────────┼──────────────────────────────────────────────────────────┤
│     │ Errors: typed exception with code,     │ One test per catalogue row asserting code, message       │
│ 7   │ message, both spans, didYouMean via    │ content, and span — stage:Intervew asserts [6,14]        │
│     │ levenshtein; handler in ProblemDetails │ literally                                                │
├─────┼────────────────────────────────────────┼──────────────────────────────────────────────────────────┤
│ 8   │ Golden file, ~60 rows, input → DSL →   │ Parameterised test over the file; the eight acceptance   │
│     │ AST                                    │ rows present verbatim                                    │
└─────┴────────────────────────────────────────┴──────────────────────────────────────────────────────────┘
 Write(src\main\java\com\pipeline\search\Span.java)
Wrote 30 lines to src\main\java\com\pipeline\search\Span.java
package com.pipeline.search;

/**
 * A half-open {@code [start, end)} character range. Every token, every AST node and every
 * error carries two of these: one into the normalised DSL and one into what the recruiter
 * actually typed. Retrofitting spans is miserable, so nothing in this package is allowed
 * to exist without them.
 */
public record Span(int start, int end) {
… +20 lines

● Write(src\main\java\com\pipeline\search\ErrorCode.java)
Wrote 29 lines to src\main\java\com\pipeline\search\ErrorCode.java
package com.pipeline.search;

/**
 * Machine-readable reasons a query was rejected. The recruiter sees the message, the UI
 * switches on the code, and the span tells it what to underline.
 */
public enum ErrorCode {
    EMPTY_QUERY,
    UNKNOWN_FIELD,
    UNKNOWN_STAGE,
… +19 lines

● Write(src\main\java\com\pipeline\search\SearchQueryException.java)
Wrote 48 lines to src\main\java\com\pipeline\search\SearchQueryException.java
package com.pipeline.search;

import java.util.List;

/**
 * A query the recruiter cannot be given results for, with everything needed to tell her
 * why: a code to switch on, a sentence to read, the characters to underline, and the
 * alternatives we could work out. Carries both spans because the normaliser rewrites her
 * sentence before it is parsed, so the offsets the parser knows about are not the offsets
 * her cursor understands.
… +39 lines

● Write(src\main\java\com\pipeline\search\Levenshtein.java)
Wrote 53 lines to src\main\java\com\pipeline\search\Levenshtein.java
package com.pipeline.search;

import java.util.Comparator;
import java.util.List;

/**
 * Edit distance, for turning {@code Intervew} back into {@code Interview}. Hand-written
 * rather than pulled in with a library: it is twenty lines, and the alternative is a
 * dependency whose only caller is this one.
 */
… +45 lines

● Write(src\main\java\com\pipeline\search\Operator.java)
Wrote 31 lines to src\main\java\com\pipeline\search\Operator.java
package com.pipeline.search;

/**
 * How a predicate compares. The written grammar puts the comparison between the field and
 * the value ({@code in_stage_for>7d}) while every query the recruiter actually types puts
 * it after the colon ({@code in_stage_for:>7d}). Both forms fold to the same operator
 * here, and {@link #render()} always emits the second, so the canonical DSL matches what
 * she sees in the box.
 */
public enum Operator {
… +21 lines

● Write(src\main\java\com\pipeline\search\Status.java)
Wrote 26 lines to src\main\java\com\pipeline\search\Status.java
package com.pipeline.search;

import com.pipeline.domain.Stage;
import java.util.Optional;

/**
 * What {@code status:} means, expressed against the pipeline rather than duplicated from
 * it: the two terminal stages are the two outcomes, and everyone else is still in play.
 * Derived rather than listed, so adding a terminal stage cannot leave this behind.
 */
… +17 lines

Now the normalizer — the piece that carries the span mapping.

● Write(src\main\java\com\pipeline\search\NormalizedQuery.java)
Wrote 76 lines to src\main\java\com\pipeline\search\NormalizedQuery.java
package com.pipeline.search;

import java.util.ArrayList;
import java.util.List;

/**
 * The canonical DSL the rest of the pipeline parses, plus the map back to what the
 * recruiter typed.
 *
 * <p>The mapping is exact wherever the normaliser copied her text through untouched, which
… +67 lines

● Write(src\main\java\com\pipeline\search\Normalizer.java)
Wrote 267 lines to src\main\java\com\pipeline\search\Normalizer.java
package com.pipeline.search;

import com.pipeline.domain.Stage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.MatchResult;
… +283 lines

● Write(src\main\java\com\pipeline\search\Token.java)
Wrote 17 lines to src\main\java\com\pipeline\search\Token.java
package com.pipeline.search;

/**
 * A lexeme and where it came from, twice over: {@code span} into the normalised DSL the
 * parser is working on, {@code source} into the query the recruiter typed.
 */
record Token(TokenType type, String text, Span span, Span source) {

    boolean is(TokenType candidate) {
        return type == candidate;
… +7 lines

● Write(src\main\java\com\pipeline\search\TokenType.java)
Wrote 31 lines to src\main\java\com\pipeline\search\TokenType.java
package com.pipeline.search;

/**
 * Keywords are deliberately absent. {@code and}, {@code or}, {@code not} and {@code except}
 * lex as ordinary words and only become connectives where the grammar expects one, so that
 * {@code name:not} searches for somebody called Not rather than failing to parse.
 */
enum TokenType {
    WORD,
    QUOTED,
… +21 lines

● Write(src\main\java\com\pipeline\search\Lexer.java)
Wrote 111 lines to src\main\java\com\pipeline\search\Lexer.java
package com.pipeline.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalised DSL to tokens. Every token gets its span here and carries it to the end;
 * nothing downstream ever has to work out where something was.
 */
final class Lexer {
… +101 lines

● Update(src\main\java\com\pipeline\search\Lexer.java)
Added 1 line, removed 3 lines
    }

    private Token next() {
        int start = position;
        char c = text.charAt(position);
        return switch (c) {
        return switch (text.charAt(position)) {
            case '"' -> quoted();
            case '(' -> single(TokenType.LEFT_PAREN);
            case ')' -> single(TokenType.RIGHT_PAREN);

● Write(src\main\java\com\pipeline\search\ResolvedValue.java)
Wrote 38 lines to src\main\java\com\pipeline\search\ResolvedValue.java
package com.pipeline.search;

import com.pipeline.domain.Stage;
import java.time.Instant;
import java.util.Optional;

/**
 * What a predicate's value turned out to mean, once the field that owns it has had a look.
 * Sealed so that file 08's builder is a total switch: a new shape of value is a compile
 * error there rather than a silently unhandled filter.
… +30 lines

● Write(src\main\java\com\pipeline\search\Node.java)
Wrote 52 lines to src\main\java\com\pipeline\search\Node.java
package com.pipeline.search;

import java.util.List;

/**
 * The parsed query. Variants are nested rather than given a file each: they are one type
 * with five shapes, not five concepts, and reading the tree in one place is worth more
 * than the symmetry.
 *
 * <p>Both spans reach every node. A parent's is the extent of its children, so underlining
… +43 lines

● Write(src\main\java\com\pipeline\search\Parser.java)
Wrote 215 lines to src\main\java\com\pipeline\search\Parser.java
package com.pipeline.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recursive descent over the grammar, lowest precedence outermost: or, and, not, primary.
 * Whitespace is a conjunction, so {@code stage:interview sharma} is two filters rather
 * than one long term.
… +211 lines

● Update(src\main\java\com\pipeline\search\Parser.java)
Added 6 lines, removed 6 lines
        return isKeyword(token, "not") || isKeyword(token, "except");
    }

    /** A word is only a connective where no operator follows it, so {@code not:x} is a field
. */
    /**
     * A word is only a connective where no operator follows it, so {@code not:x} is a
     * field rather than a negated nothing. Only ever asked about the current token, which
     * is why the lookahead is a fixed one ahead.
     */
    private boolean isKeyword(Token token, String keyword) {
        return token.isWord(keyword) && !peekAt(offsetOf(token) + 1).type().isOperator();
        return token.isWord(keyword) && !peekAt(1).type().isOperator();
    }

    private int offsetOf(Token token) {
        return tokens.get(position) == token ? 0 : 1;
    }

    private boolean startsPrimary(Token token) {
        if (isOr(token) || isAnd(token)) {
            return false;

● Write(src\main\java\com\pipeline\search\Dsl.java)
Wrote 45 lines to src\main\java\com\pipeline\search\Dsl.java
package com.pipeline.search;

import java.util.stream.Collectors;

/**
 * The AST back out as canonical DSL. Rendering from the tree rather than handing back
 * whatever the normaliser produced is what makes the canonical form exact: spacing,
 * quoting and the choice between {@code except} and {@code -} are decided in one place,
 * so two queries that mean the same thing print the same way.
 */
… +41 lines

● Write(src\main\java\com\pipeline\search\QueryGuards.java)
Wrote 86 lines to src\main\java\com\pipeline\search\QueryGuards.java
package com.pipeline.search;

import java.util.List;

/**
 * A query language is an attack surface, so the work is bounded before any of it is done.
 * All three limits are checked without parsing: length against the raw input, which is
 * what an attacker controls, and the other two against the token stream, where a predicate
 * is a field with an operator and nesting is just paren balance.
 */
… +78 lines

● Write(src\main\java\com\pipeline\search\QueryGuards.java)
Added 27 lines, removed 28 lines

    static void checkLength(String raw) {
        if (raw.length() > MAX_LENGTH) {
            Span span = new Span(MAX_LENGTH, raw.length());
            throw new SearchQueryException(ErrorCode.QUERY_TOO_LONG,
                    "That query is " + raw.length() + " characters. The limit is " + MAX_LENGT
H + ".",
                    new Span(MAX_LENGTH, raw.length()), new Span(MAX_LENGTH, raw.length()));
                    "That query is " + raw.length() + " characters. The limit is " + MAX_LENGT
H + ".", span, span);
        }
    }

    static void checkShape(List<Token> tokens, NormalizedQuery query) {
        checkPredicates(tokens, query);
        checkDepth(tokens, query);
    static void checkShape(List<Token> tokens) {
        checkPredicates(tokens);
        checkDepth(tokens);
    }

    private static void checkPredicates(List<Token> tokens, NormalizedQuery query) {
    private static void checkPredicates(List<Token> tokens) {
        int count = 0;
        int i = 0;
        while (i < tokens.size() && !tokens.get(i).is(TokenType.END)) {
            Token token = tokens.get(i);
            if (isValueCarrier(token)) {
        while (!at(tokens, i).is(TokenType.END)) {
            Token token = at(tokens, i);
            if (token.is(TokenType.WORD) || token.is(TokenType.QUOTED)) {
                // A bare term filters as much as a predicate does and costs as much to
                // run, so it counts against the same budget.
                count++;
                i += lengthOfPredicate(tokens, i);
                if (count > MAX_PREDICATES) {
                    throw new SearchQueryException(ErrorCode.TOO_MANY_PREDICATES,
                            "That query has more than " + MAX_PREDICATES + " conditions. Narro
w it down.",
                            token.span(), token.source());
                }
                i += predicateLength(tokens, i);
            } else {
                i++;
            }
            if (count > MAX_PREDICATES) {
                throw new SearchQueryException(ErrorCode.TOO_MANY_PREDICATES,
                        "That query has more than " + MAX_PREDICATES + " conditions. Narrow it
 down.",
                        token.span(), token.source());
            }
        }
    }

    private static boolean isValueCarrier(Token token) {
        return token.is(TokenType.WORD) || token.is(TokenType.QUOTED);
    }

    private static int lengthOfPredicate(List<Token> tokens, int start) {
        if (start + 1 >= tokens.size() || !tokens.get(start + 1).type().isOperator()) {
    /** How many tokens the predicate starting here occupies, so its value is not counted twic
e. */
    private static int predicateLength(List<Token> tokens, int start) {
        if (!at(tokens, start + 1).type().isOperator()) {
            return 1;
        }
        int length = 2;
        if (tokens.get(start + length).type().isComparison()) {
            length++;
        }
        Token value = tokens.get(start + length);
        int length = at(tokens, start + 2).type().isComparison() ? 3 : 2;
        Token value = at(tokens, start + length);
        boolean hasValue = value.is(TokenType.QUOTED)
                || value.is(TokenType.WORD) && !tokens.get(start + length + 1).type().isOperat
or();
                || value.is(TokenType.WORD) && !at(tokens, start + length + 1).type().isOperat
or();
        return hasValue ? length + 1 : length;
    }

    private static void checkDepth(List<Token> tokens, NormalizedQuery query) {
    private static void checkDepth(List<Token> tokens) {
        int depth = 0;
        for (Token token : tokens) {
            if (token.is(TokenType.LEFT_PAREN)) {

        }
    }

    /** The stream always ends with END, so reading past the end reads END rather than failing
. */
    private static Token at(List<Token> tokens, int index) {
        return tokens.get(Math.min(index, tokens.size() - 1));
    }

    private QueryGuards() {}
}

 Write(src\main\java\com\pipeline\search\Durations.java)
Wrote 44 lines to src\main\java\com\pipeline\search\Durations.java
package com.pipeline.search;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
… +35 lines

● Write(src\main\java\com\pipeline\search\Dates.java)
Wrote 78 lines to src\main\java\com\pipeline\search\Dates.java
package com.pipeline.search;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
… +69 lines

● Write(src\main\java\com\pipeline\search\Stages.java)
Wrote 31 lines to src\main\java\com\pipeline\search\Stages.java
package com.pipeline.search;

import com.pipeline.domain.Stage;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Stage names as the recruiter writes them. Shared by every field that takes one, so a
 * misspelling is reported the same way whichever field she misspelled it in.
… +22 lines

● Write(src\main\java\com\pipeline\search\FieldHandler.java)
Wrote 55 lines to src\main\java\com\pipeline\search\FieldHandler.java
package com.pipeline.search;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One searchable field, and everything that is specific to it: which comparisons it
 * accepts, what its values mean, and what to suggest when she leaves one out. Nothing
… +45 lines

● Write(src\main\java\com\pipeline\search\Statuses.java)
Wrote 24 lines to src\main\java\com\pipeline\search\Statuses.java
package com.pipeline.search;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** {@link Stages}, for the other closed vocabulary the search box has. */
public final class Statuses {

    public static Status resolve(Node.Value value) {
… +16 lines

● Write(src\main\java\com\pipeline\search\FieldRegistry.java)
Wrote 58 lines to src\main\java\com\pipeline\search\FieldRegistry.java
package com.pipeline.search;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
… +50 lines

● Update(src\main\java\com\pipeline\search\FieldRegistry.java)
Removed 8 lines
    public boolean isModifier(String field) {
        return modifierFields.contains(field);
    }

    SearchQueryException unknownField(Node.Predicate predicate) {
        Span span = new Span(predicate.span().start(), predicate.span().start() + predicate.fi
eld().length());
        return new SearchQueryException(ErrorCode.UNKNOWN_FIELD,
                "There is no field called \"" + predicate.field() + "\". Valid fields are "
                        + String.join(", ", fields()) + ".",
                span, predicate.source(), Levenshtein.closest(predicate.field(), fields()));
    }
}

● Write(src\main\java\com\pipeline\search\Stages.java)
Added 7 lines, removed 2 lines
                .findFirst()
                .orElseThrow(() -> new SearchQueryException(ErrorCode.UNKNOWN_STAGE,
                        "There is no stage called \"" + value.text() + "\".",
                        value.span(), value.source(), Levenshtein.closest(value.text(), names(
))));
                        value.span(), value.source(), Levenshtein.closest(value.text(), displa
yNames())));
    }

    /** Title case, because that is how the board labels them and how a suggestion should read
. */
    /** Canonical DSL form, which is what autocomplete should offer and examples should use. *
/
    public static List<String> names() {
        return Arrays.stream(Stage.values()).map(stage -> stage.name().toLowerCase(Locale.ROOT
)).toList();
    }

    /** Title case, because that is how the board labels a stage and how a suggestion should r
ead. */
    public static List<String> displayNames() {
        return Arrays.stream(Stage.values())
                .map(stage -> stage.name().charAt(0) + stage.name().substring(1).toLowerCase(L
ocale.ROOT))
                .toList();

● Update(src\main\java\com\pipeline\search\FieldHandler.java)
Added 6 lines
    ResolvedValue resolve(Node.Value value, Operator operator, Clock clock);

    /**
     * What kind of thing the value is, in the words an error message uses: "a date", "a
     * stage". Reads straight into "since: needs a date".
     */
    String valueKind();

    /**
     * Values worth showing her, used for the missing-value message and for autocomplete in
     * file 08. First one wins as the example in an error.
     */

Now the validator and the facade.

● Write(src\main\java\com\pipeline\search\Validator.java)
Wrote 155 lines to src\main\java\com\pipeline\search\Validator.java
package com.pipeline.search;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
… +157 lines

● Update(src\main\java\com\pipeline\search\Validator.java)
Added 4 lines, removed 9 lines
    }

    private SearchQueryException unsupportedOperator(Node.Predicate predicate, FieldHandler h
andler) {
        String accepted = handler.operators().stream().anyMatch(Operator::isComparison)
                ? "a comparison"
                : "no comparison";
        return new SearchQueryException(ErrorCode.UNSUPPORTED_OPERATOR,
                predicate.field() + ": takes " + accepted + ". Try " + example(handler) + "."
,
                predicate.span(), predicate.source());
                predicate.field() + ": cannot be compared with " + predicate.operator().rende
r()
                        + ". Try " + example(handler) + ".",
                predicate.span(), predicate.source(), List.of(example(handler)));
    }

    private SearchQueryException missingValue(Node.Predicate predicate, FieldHandler handler)
 {

    }

    private static String example(FieldHandler handler) {
        return handler.field() + ":" + Optional.of(handler.examples())
                .filter(examples -> !examples.isEmpty())
                .map(examples -> examples.get(0))
                .orElse("");
        return handler.field() + ":" + handler.examples().get(0);
    }
}

● Update(src\main\java\com\pipeline\search\Validator.java)
Removed 1 line
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Semantics. The parser has already decided the query is well formed; this decides whether

● Write(src\main\java\com\pipeline\search\SearchQuery.java)
Wrote 8 lines to src\main\java\com\pipeline\search\SearchQuery.java
package com.pipeline.search;

/**
 * A query that parsed and means something: what she typed, what it was read as, and the
 * tree file 08 turns into SQL. Only ever built from a validated AST, which is why nothing
 * downstream has to check whether a value was resolved.
 */
public record SearchQuery(String raw, String dsl, Node ast) {}

● Write(src\main\java\com\pipeline\search\SearchQueryParser.java)
Wrote 50 lines to src\main\java\com\pipeline\search\SearchQueryParser.java
package com.pipeline.search;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The whole pipeline, in the order the stages were designed: normalise, lex, guard, parse,
 * validate. The only entry point — everything else in this package is a stage of it.
 */
… +40 lines

● Write(src\main\java\com\pipeline\search\fields\StageField.java)
Wrote 35 lines to src\main\java\com\pipeline\search\fields\StageField.java
package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
… +25 lines

● Write(src\main\java\com\pipeline\search\fields\ReachedField.java)
Wrote 39 lines to src\main\java\com\pipeline\search\fields\ReachedField.java
package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
… +29 lines

● Write(src\main\java\com\pipeline\search\fields\MovedToField.java)
Wrote 57 lines to src\main\java\com\pipeline\search\fields\MovedToField.java
package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import java.time.Clock;
import java.util.List;
import java.util.Map;
… +50 lines

● Write(src\main\java\com\pipeline\search\fields\SinceField.java)
Wrote 35 lines to src\main\java\com\pipeline\search\fields\SinceField.java
package com.pipeline.search.fields;

import com.pipeline.search.Dates;
import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
… +25 lines

● Write(src\main\java\com\pipeline\search\fields\BeforeField.java)
Wrote 35 lines to src\main\java\com\pipeline\search\fields\BeforeField.java
package com.pipeline.search.fields;

import com.pipeline.search.Dates;
import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
… +25 lines

● Write(src\main\java\com\pipeline\search\fields\InStageForField.java)
Wrote 51 lines to src\main\java\com\pipeline\search\fields\InStageForField.java
package com.pipeline.search.fields;

import com.pipeline.search.Durations;
import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import java.time.Clock;
import java.util.List;
import java.util.Set;
… +42 lines

● Write(src\main\java\com\pipeline\search\fields\AppliedField.java)
Wrote 46 lines to src\main\java\com\pipeline\search\fields\AppliedField.java
package com.pipeline.search.fields;

import com.pipeline.search.Durations;
import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import java.time.Clock;
import java.util.List;
import java.util.Set;
… +37 lines

● Write(src\main\java\com\pipeline\search\fields\StatusField.java)
Wrote 35 lines to src\main\java\com\pipeline\search\fields\StatusField.java
package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Statuses;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
… +25 lines

● Write(src\main\java\com\pipeline\search\fields\NameField.java)
Wrote 38 lines to src\main\java\com\pipeline\search\fields\NameField.java
package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
… +28 lines

  Read 1 file

● Write(src\main\java\com\pipeline\search\package-info.java)
Wrote 5 lines to src\main\java\com\pipeline\search\package-info.java
/**
 * The search query language: normaliser, lexer, parser, validator. No SQL — turning the
 * AST into a query is file 08's job, and lives behind {@link com.pipeline.search.FieldHandler}.
 */
package com.pipeline.search;

● Write(src\main\java\com\pipeline\search\fields\package-info.java)
Wrote 5 lines to src\main\java\com\pipeline\search\fields\package-info.java
/**
 * One class per searchable field. Nothing else in the application names a field, so adding
 * one is adding a class here.
 */
package com.pipeline.search.fields;

Now the Problem Details handler, then compile.

● Update(src\main\java\com\pipeline\api\ProblemDetails.java)
Added 19 lines
                        e.getMessage(), request));
    }

    /**
     * A query that cannot be answered, carrying the part of it that is wrong. The spans
     * are what let the search box underline the offending characters rather than clear
     * itself and say "invalid": {@code source} indexes the text she typed, {@code span}
     * the normalised DSL that {@code /explain} shows her.
     */
    @ExceptionHandler(SearchQueryException.class)
    ResponseEntity<ProblemDetail> badQuery(SearchQueryException e, HttpServletRequest request
) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, e.code().slug(), "Ca
nnot run that search",
                e.getMessage(), request);
        problem.setProperty("code", e.code().name());
        problem.setProperty("span", List.of(e.source().start(), e.source().end()));
        problem.setProperty("normalisedSpan", List.of(e.span().start(), e.span().end()));
        if (!e.didYouMean().isEmpty()) {
            problem.setProperty("didYouMean", e.didYouMean());
        }
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(NoJobConfiguredException.class)
    ResponseEntity<ProblemDetail> noJob(NoJobConfiguredException e, HttpServletRequest reques
t) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)

● Update(src\main\java\com\pipeline\api\ProblemDetails.java)
Added 2 lines
import com.pipeline.application.StaleCandidateStateException;
import com.pipeline.domain.IllegalStageTransitionException;
import com.pipeline.domain.Stage;
import com.pipeline.search.SearchQueryException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;

  Ran 2 shell commands

Main sources compile. Now the tests — fixture, golden file, and the error catalogue.

● Write(src\test\java\com\pipeline\search\fields\SearchFixture.java)
Wrote 47 lines to src\test\java\com\pipeline\search\fields\SearchFixture.java
package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.FieldRegistry;
import com.pipeline.search.SearchQueryParser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
… +37 lines

● Write(src\test\java\com\pipeline\search\AstPrinter.java)
Wrote 53 lines to src\test\java\com\pipeline\search\AstPrinter.java
package com.pipeline.search;

import java.util.stream.Collectors;

/**
 * The AST as an s-expression, for the golden file. Prints the resolved value rather than
 * the text it came from, so a row proves the validator ran; prints the literal rather than
 * the instant, so the file does not have to be rewritten whenever the fixed clock moves.
 */
final class AstPrinter {
… +45 lines

● Write(src\test\resources\search\golden.txt)
Wrote 293 lines to src\test\resources\search\golden.txt
# Input, the canonical DSL it normalises to, and the validated AST.
#
# The AST prints resolved values, so a row proves the validator ran, and prints date and
# duration literals rather than instants, so the file survives the fixed clock moving.
# Blocks are separated by blank lines; # starts a comment.
#
# ---------------------------------------------------------------------------------------
# The eight acceptance rows from prompts/07, verbatim.
# ---------------------------------------------------------------------------------------
… +283 lines

● Write(src\test\java\com\pipeline\search\GoldenQueryTest.java)
Wrote 94 lines to src\test\java\com\pipeline\search\GoldenQueryTest.java
package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.search.fields.SearchFixture;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
… +84 lines

  Listed 1 directory, ran 1 shell command

Golden file passes: 67 rows, 135 assertions. Now restructuring the validator so a missing value is reported before a bad one — that's what makes the prompt's moved_to:x since: row literally true.

● Update(src\main\java\com\pipeline\search\Validator.java)
Added 23 lines
    }

    Node validate(Node node) {
        // Structure before meaning, for the same reason the parser runs before the
        // validator: "you left the value off since:" is a more useful thing to be told
        // than "x is not a stage" when the query has both problems, because the first is
        // a half-finished thought and the second is a typo in a finished one.
        checkShape(node);
        Node resolved = attach(resolve(node));
        rejectUnattachedModifiers(resolved);
        return resolved;
    }

    private void checkShape(Node node) {
        switch (node) {
            case Node.And and -> and.children().forEach(this::checkShape);
            case Node.Or or -> or.children().forEach(this::checkShape);
            case Node.Not not -> checkShape(not.child());
            case Node.Predicate predicate -> {
                FieldHandler handler = registry.find(predicate.field()).orElseThrow(() -> unkn
ownField(predicate));
                if (!handler.operators().contains(predicate.operator())) {
                    throw unsupportedOperator(predicate, handler);
                }
                if (predicate.value().isMissing()) {
                    throw missingValue(predicate, handler);
                }
            }
            case Node.Term term -> { }
        }
    }

    private Node resolve(Node node) {
        return switch (node) {
            case Node.And and -> new Node.And(resolveAll(and.children()), and.span(), and.sour
ce());

● Update(src\main\java\com\pipeline\search\Validator.java)
Added 2 lines, removed 7 lines
        return children.stream().map(this::resolve).toList();
    }

    /** Shape is already checked, so the handler is present and the value is there to read. */
    private Node resolve(Node.Predicate predicate) {
        FieldHandler handler = registry.find(predicate.field()).orElseThrow(() -> unknownField
(predicate));
        if (!handler.operators().contains(predicate.operator())) {
            throw unsupportedOperator(predicate, handler);
        }
        if (predicate.value().isMissing()) {
            throw missingValue(predicate, handler);
        }
        FieldHandler handler = registry.find(predicate.field()).orElseThrow();
        return predicate.resolvedAs(handler.resolve(predicate.value(), predicate.operator(), c
lock));
    }


Now the error catalogue and span tests.

● Write(src\test\java\com\pipeline\search\SearchErrorTest.java)
Wrote 144 lines to src\test\java\com\pipeline\search\SearchErrorTest.java
package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pipeline.search.fields.SearchFixture;
import org.junit.jupiter.api.Test;

/**
 * The error catalogue, asserting the span as hard as the message. A wrong span is the
… +137 lines

  Ran 1 shell command

● Write(src\test\java\com\pipeline\search\SpanTest.java)
Wrote 88 lines to src\test\java\com\pipeline\search\SpanTest.java
package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.search.fields.SearchFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spans, on their own, because everything else in the package would still pass if they
… +79 lines

● Write(src\test\java\com\pipeline\search\QueryGuardTest.java)
Wrote 100 lines to src\test\java\com\pipeline\search\QueryGuardTest.java
package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pipeline.search.fields.SearchFixture;
import java.util.Random;
import org.junit.jupiter.api.Test;
… +93 lines

● Write(src\test\java\com\pipeline\search\ValueResolutionTest.java)
Wrote 115 lines to src\test\java\com\pipeline\search\ValueResolutionTest.java
package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.search.fields.SearchFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
… +111 lines

● Write(src\test\java\com\pipeline\search\FieldRegistryTest.java)
Wrote 78 lines to src\test\java\com\pipeline\search\FieldRegistryTest.java
package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipeline.search.fields.SearchFixture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
… +70 lines

  Ran 2 shell commands

That failure is real: the normalizer was inserting a space where the source had none, so -status:rejected became - status:rejected. Fixing the join rule.

● Update(src\main\java\com\pipeline\search\Normalizer.java)
Added 17 lines, removed 12 lines
        return result;
    }

    /** Joins what survived with single spaces, recording where each run came from. */
    /**
     * Joins what survived, recording where each run came from. Two runs are separated by a
     * single space only where something separated them in the query — whitespace, or text
     * a rule removed. Where they were touching they stay touching, which is what keeps
     * {@code -status:rejected} from becoming {@code - status:rejected} and, more to the
     * point, keeps an all-DSL query identical to what she typed, spans included.
     */
    private static NormalizedQuery emit(String raw, List<Segment> segments) {
        StringBuilder text = new StringBuilder();
        List<NormalizedQuery.Piece> pieces = new ArrayList<>();
        int previousSourceEnd = -1;
        for (Segment segment : segments) {
            String body = segment.text();
            int lead = leadingSpace(body);
            String content = body.strip();
            int lead = leadingSpace(segment.text());
            String content = segment.text().strip();
            if (content.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
            boolean rewritten = segment.kind() == Kind.REWRITTEN;
            int sourceStart = rewritten ? segment.sourceStart() : segment.sourceStart() + lea
d;
            int sourceEnd = rewritten ? segment.sourceEnd() : sourceStart + content.length();

            if (!text.isEmpty() && sourceStart > previousSourceEnd) {
                text.append(' ');
            }
            int outStart = text.length();
            text.append(content);
            boolean rewritten = segment.kind() == Kind.REWRITTEN;
            pieces.add(new NormalizedQuery.Piece(
                    outStart,
                    text.length(),
                    rewritten ? segment.sourceStart() : segment.sourceStart() + lead,
                    rewritten ? segment.sourceEnd() : segment.sourceStart() + lead + content.
length(),
                    rewritten));
            pieces.add(new NormalizedQuery.Piece(outStart, text.length(), sourceStart, source
End, rewritten));
            previousSourceEnd = sourceEnd;
        }
        return new NormalizedQuery(raw, text.toString(), pieces);
    }

● Update(src\test\java\com\pipeline\search\SpanTest.java)
Added 7 lines
    private final SearchQueryParser parser = SearchFixture.parser();

    @Test
    void aQueryAlreadyInTheDslPassesThroughUntouched() {
        String query = "stage:interview in_stage_for:>3d -status:rejected";

        assertThat(Normalizer.normalise(query).text()).isEqualTo(query);
    }

    @Test
    void everyTokenKnowsExactlyWhichCharactersItIs() {
        String query = "stage:interview in_stage_for:>3d -status:rejected";
        List<Token> tokens = new Lexer(Normalizer.normalise(query)).tokens();

  Ran 1 shell command

● Update(src\test\java\com\pipeline\ArchitectureTest.java)
Added 15 lines
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.pipeline.infrastructure..", "com.pipeline.api..");

    // The open/closed claim the search design rests on, enforced rather than asserted in
    // a comment: if the normaliser, lexer, parser or validator ever reached for a handler,
    // adding a searchable field would stop being one new class.
    @ArchTest
    static final ArchRule theQueryPipelineKnowsNoFieldByName = noClasses()
            .that().resideInAPackage("com.pipeline.search")
            .should().dependOnClassesThat().resideInAPackage("com.pipeline.search.fields..");

    // The other half of it: a handler nobody registered is a field that silently does not
    // exist, and the query would fail with "no such field" rather than at startup.
    @ArchTest
    static final ArchRule everyFieldHandlerIsRegistered = classes()
            .that().implement(FieldHandler.class)
            .should().beAnnotatedWith(Component.class);

    // The ports live in application and the adapters implement them, so the arrow runs
    // inward. A use case reaching for a JPA repository would reverse it, and nothing
    // else in the build would notice.

● Update(src\test\java\com\pipeline\ArchitectureTest.java)
Added 3 lines
package com.pipeline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.pipeline.search.FieldHandler;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Component;

/**
 * Production code only. A test for a domain class is entitled to use JUnit and AssertJ;

  Listed 1 directory, ran 3 shell commands

Full suite green: 337 tests, 30 classes, 0 failures. Two small tidy-ups before I stop.

● Update(src\test\java\com\pipeline\search\AstPrinter.java)
Added 1 line
package com.pipeline.search;

import java.util.List;
import java.util.stream.Collectors;

/**

● Update(src\test\java\com\pipeline\search\AstPrinter.java)
Added 2 lines, removed 2 lines

    static String print(Node node) {
        return switch (node) {
            case Node.And and -> "(and " + children(and.children().stream()) + ")";
            case Node.Or or -> "(or " + children(or.children().stream()) + ")";
            case Node.And and -> "(and " + children(and.children()) + ")";
            case Node.Or or -> "(or " + children(or.children()) + ")";
            case Node.Not not -> "(not " + print(not.child()) + ")";
            case Node.Term term -> "(term " + term.text() + ")";
            case Node.Predicate predicate -> "(" + predicate.field() + " " + symbol(effectiveO
perator(predicate))

● Update(src\test\java\com\pipeline\search\AstPrinter.java)
Added 2 lines, removed 2 lines
        return operator == Operator.EQUALS ? "=" : operator.render();
    }

    private static String children(java.util.stream.Stream<Node> nodes) {
        return nodes.map(AstPrinter::print).collect(Collectors.joining(" "));
    private static String children(List<Node> nodes) {
        return nodes.stream().map(AstPrinter::print).collect(Collectors.joining(" "));
    }

    private AstPrinter() {}

● Update(src\test\java\com\pipeline\search\ValueResolutionTest.java)
Added 1 line

import com.pipeline.search.fields.SearchFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

● Update(src\test\java\com\pipeline\search\ValueResolutionTest.java)
Added 2 lines, removed 2 lines
    @Test
    void aWeekdayThatIsTodayMeansToday() {
        assertThat(movedTo("moved_to:offer since:wednesday").since())
                .hasValueSatisfying(date -> assertThat(date.instant()).isEqualTo(SearchFixture
.NOW
                        .truncatedTo(java.time.temporal.ChronoUnit.DAYS)));
                .hasValueSatisfying(date ->
                        assertThat(date.instant()).isEqualTo(SearchFixture.NOW.truncatedTo(Chr
onoUnit.DAYS)));
    }

    @ParameterizedTest

     Update(src\test\java\com\pipeline\search\FieldRegistryTest.java)
Removed 4 lines

    @Test
    void twoHandlersClaimingOneFieldFailAtStartupRatherThanAtQueryTime() {
        List<FieldHandler> duplicated = new ArrayList<>(SearchFixture.handlers());
        duplicated.add(new SourceField());

        assertThatThrownBy(() -> new FieldRegistry(List.of(new SourceField(), new SourceField(
))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source");
        assertThat(new FieldRegistry(duplicated).fields()).contains("source");
    }

    /**

  Ran 1 shell command

Done. Full suite green: 337 tests, 0 failures, of which 190 are new.

What's there

com.pipeline.search — normalizer → lexer → parser → validator, and com.pipeline.search.fields — nine handlers, one per field. Plus one @ExceptionHandler in the existing ProblemDetails, so a bad query comes out of the same envelope as every other error (422, with code, span, normalisedSpan, didYouMean). No SQL, no JPA import anywhere in the package.

Verification, against the plan

┌────────────────┬───────────────────────────────────────────────────────────────────────────────────────┐
│      Step      │                                        Result                                         │
├────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ 1 Spans +      │ Token spans exact for stage:interview in_stage_for:>3d -status:rejected; >= munches   │
│ lexer          │ long, jean-luc stays one word                                                         │
├────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ 2 Normalizer + │ All eight acceptance rows exact; an all-DSL query is byte-identical through the       │
│  span map      │ normalizer; verbatim text maps character-exact, rewritten phrases map to the whole    │
│                │ phrase                                                                                │
├────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ 3 Parser +     │ 67 golden rows, and each one re-parses from its own canonical form to itself          │
│ renderer       │                                                                                       │
├────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ 4 Guards       │ 512 passes / 513 fails, 12 passes / 13 fails, depth 5 passes / 6 fails, 500 negations │
│                │  terminate, 5 000 seeded random inputs all stop as query errors                       │
├────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ 5 Registry +   │ Duplicate field fails in the constructor; ArchUnit: nothing in com.pipeline.search    │
│ handlers       │ may depend on ..fields, and every FieldHandler must be @Component                     │
├────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ 6 Validator    │ Fixed clock (Wed 2025-03-12); monday, last monday, this week, today, ISO all          │
│                │ asserted; age comparison asserted in both directions; modifiers don't cross an OR     │
├────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ 7 Errors       │ 14 tests, every catalogue row, stage:Intervew asserted at [6,14] literally            │
├────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ 8 Golden file  │ src/test/resources/search/golden.txt, eight acceptance rows verbatim at the top       │
└────────────────┴───────────────────────────────────────────────────────────────────────────────────────┘

Things you should know about

I ran your file-10 test early. FieldRegistryTest adds a throwaway source:referral handler to a registry and parses stage:interview source:referral. It works with no change to lexer, parser, validator or guards — the design holds, and now a regression in it fails here rather than in file 10.

moved_to:x since: needed a decision. Resolving values in source order would have reported UNKNOWN_STAGE on x first, not the MISSING_VALUE your catalogue names. I made the validator check shape across the whole tree — field known, operator accepted, value present — before resolving any value, on the grounds that "you left the value off" is a half-finished thought and "that isn't a stage" is a typo in a finished one. Your row is now literally true.

Two deviations I flagged and took. FieldRegistry builds its map from List<FieldHandler> keyed on field() rather than taking Spring's bean-name-keyed Map. And the parse is bounded by a step budget rather than a wall clock, as you chose.

One new error code, UNATTACHED_MODIFIER: a since: with no moved_to: beside it is rejected rather than ignored.

Durations canonicalise weeks to days (a week → 7d, two weeks → 14d) but keep months as months, since 3mo is a calendar statement and 90 days isn't the same thing. 2w typed directly still parses.

Everything is UTC, from the clock bean. For one recruiter that's a trade, not a bug — noted in Dates, and the fix belongs with multi-tenancy.

I left ai-logs/07-search-parsing.md empty — the earlier logs are verbatim transcripts, which is yours to paste rather than mine to reconstruct.
 -->
