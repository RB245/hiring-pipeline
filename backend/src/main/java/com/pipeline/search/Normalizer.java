package com.pipeline.search;

import com.pipeline.domain.Stage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns a sentence into the DSL, by an ordered table of rewrites and nothing else. No
 * model, no scoring, no guessing: a rule either matches or it does not, the same way every
 * time, and a query that matches no rule comes out the other side unchanged rather than
 * reinterpreted.
 *
 * <p>Two properties make the table safe to extend. Regions that are already DSL — a
 * {@code field:value} pair or a quoted string — are found first and never rewritten, which
 * is what keeps a query she typed in the DSL byte-identical on the way through. And a
 * region a rule has rewritten is closed to every later rule, so rules compose by
 * consuming text rather than by layering on top of each other.
 *
 * <p>Order is the whole design. Structural phrases claim their stage and status words
 * before the bare-word rules can see them, filler goes before the name rule so a leading
 * "Find" is not mistaken for a first name, and the name rule runs last on whatever is left.
 */
final class Normalizer {

    private static final Pattern ALREADY_DSL =
            Pattern.compile("\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_]*\\s*[:=]\\s*(?:\"[^\"]*\"|[^\\s()]*)");

    private static final String STAGES = alternation(Arrays.stream(Stage.values()).map(Enum::name));
    private static final String STATUSES = alternation(Arrays.stream(Status.values()).map(Enum::name));

    private static final String NUMBER = "\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten";
    private static final String UNIT = "days?|weeks?|months?|mo|d|w";
    private static final String DURATION = "(?:" + NUMBER + ")\\s*(?:" + UNIT + ")";
    private static final String WEEKDAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday";
    // Longest first: "last monday" has to win over "monday", and an ISO date over nothing.
    private static final String DATE =
            "\\d{4}-\\d{2}-\\d{2}|today|yesterday|this\\s+week|last\\s+week|last\\s+(?:" + WEEKDAY + ")|" + WEEKDAY;

    /**
     * Words that carry no filter. Every preposition here has already had its chance to
     * mean something — the structural rules run first and consume the ones that did — so
     * what reaches this rule is grammar, and leaving it in would search names for "in".
     */
    private static final String FILLER = "\\b(?:who'?s|whos|who|whom|whose|which|what|show(?:ing)?|list|find|get"
            + "|search\\s+for|give\\s+me|me|all|everyone|everybody|anyone|anybody|people|candidates?|applicants?"
            + "|persons?|the|an?|is|are|was|were|be|been|being|has|have|had|does|did|do|right\\s+now|currently"
            + "|now|still|but|that|those|stage|into|in|at|for|to|from)\\b|[?!]|\\.(?=\\s|$)";

    private static final String NAME_WORD =
            "(?!(?i:" + STAGES + "|" + STATUSES + "|" + WEEKDAY + ")\\b)\\p{Lu}[\\p{L}'\\-]+";

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10));

    private static final List<Rewrite> TABLE = List.of(
            ci("\\b(?:moved?|moves|moving|went|advanced|progressed)\\s+(?:to|into)\\s+(?:the\\s+)?(" + STAGES
                    + ")\\b(?:\\s+stage)?", m -> "moved_to:" + lower(m.group(1))),

            ci("\\b(?:reached|got\\s+to|made\\s+it\\s+to|ever\\s+in|has\\s+been\\s+in|have\\s+been\\s+in)"
                    + "\\s+(?:the\\s+)?(" + STAGES + ")\\b(?:\\s+stage)?", m -> "reached:" + lower(m.group(1))),

            ci("\\b(?:stuck\\s+in|sitting\\s+in|waiting\\s+in|currently\\s+in|now\\s+in|still\\s+in|in|at)"
                    + "\\s+(?:the\\s+)?(" + STAGES + ")\\b(?:\\s+stage)?", m -> "stage:" + lower(m.group(1))),

            // Before the bare-status rule, or "didn't get hired" would come out as a
            // positive filter on the very thing she is excluding.
            ci("\\b(?:but\\s+|and\\s+)?(?:didn'?t|did\\s+not|never|wasn'?t|weren'?t|hasn'?t|haven'?t|not)"
                    + "\\s+(?:get\\s+|been\\s+|being\\s+)?(" + STATUSES + ")\\b", m -> "-status:" + lower(m.group(1))),

            ci("\\b(?:applied|joined)\\s+(?:more\\s+than|over)\\s+(" + DURATION + ")\\s+ago\\b",
                    m -> "applied:>" + duration(m.group(1))),

            ci("\\b(?:applied|joined)\\s+(?:in\\s+the\\s+last|within\\s+the\\s+last|in\\s+the\\s+past|within"
                    + "|less\\s+than)\\s+(" + DURATION + ")\\b", m -> "applied:<" + duration(m.group(1))),

            ci("\\bfor\\s+(more\\s+than|at\\s+least|longer\\s+than|over|less\\s+than|under|at\\s+most"
                    + "|no\\s+more\\s+than)?\\s*(" + DURATION + ")\\b",
                    m -> "in_stage_for:" + comparator(m.group(1)) + duration(m.group(2))),

            ci("\\b(?:since|after|from)\\s+(" + DATE + ")\\b", m -> "since:" + date(m.group(1))),
            ci("\\b(?:before|until|up\\s+to|prior\\s+to)\\s+(" + DATE + ")\\b", m -> "before:" + date(m.group(1))),

            ci("\\b(" + STATUSES + ")(?:\\s+(?:candidates?|people|applicants?))?\\b", m -> "status:" + lower(m.group(1))),

            ci(FILLER, m -> ""),

            // Case-sensitive, and last: two or more capitalised words with nothing left to
            // be but a person. A single capitalised word stays a bare term, because one
            // word is as likely to be a company or a skill as a surname.
            cs("\\b(" + NAME_WORD + "(?:\\s+" + NAME_WORD + ")+)\\b",
                    m -> "name:\"" + lower(collapse(m.group(1))) + "\""));

    private record Rewrite(Pattern pattern, Function<MatchResult, String> emit) {}

    private enum Kind { FREE, PROTECTED, REWRITTEN }

    private record Segment(String text, int sourceStart, int sourceEnd, Kind kind) {}

    static NormalizedQuery normalise(String raw) {
        List<Segment> segments = protectExistingDsl(raw);
        for (Rewrite rewrite : TABLE) {
            segments = apply(rewrite, segments);
        }
        return emit(raw, segments);
    }

    /** Splits the raw query into the parts that are already DSL and the prose between them. */
    private static List<Segment> protectExistingDsl(String raw) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = ALREADY_DSL.matcher(raw);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                segments.add(slice(raw, cursor, matcher.start(), Kind.FREE));
            }
            segments.add(slice(raw, matcher.start(), matcher.end(), Kind.PROTECTED));
            cursor = matcher.end();
        }
        if (cursor < raw.length()) {
            segments.add(slice(raw, cursor, raw.length(), Kind.FREE));
        }
        return segments;
    }

    private static List<Segment> apply(Rewrite rewrite, List<Segment> segments) {
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.kind() != Kind.FREE) {
                result.add(segment);
                continue;
            }
            Matcher matcher = rewrite.pattern().matcher(segment.text());
            int cursor = 0;
            while (matcher.find()) {
                if (matcher.start() > cursor) {
                    result.add(sub(segment, cursor, matcher.start(), Kind.FREE));
                }
                result.add(new Segment(rewrite.emit().apply(matcher),
                        segment.sourceStart() + matcher.start(), segment.sourceStart() + matcher.end(),
                        Kind.REWRITTEN));
                cursor = matcher.end();
            }
            if (cursor < segment.text().length()) {
                result.add(sub(segment, cursor, segment.text().length(), Kind.FREE));
            }
        }
        return result;
    }

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
            int lead = leadingSpace(segment.text());
            String content = segment.text().strip();
            if (content.isEmpty()) {
                continue;
            }
            boolean rewritten = segment.kind() == Kind.REWRITTEN;
            int sourceStart = rewritten ? segment.sourceStart() : segment.sourceStart() + lead;
            int sourceEnd = rewritten ? segment.sourceEnd() : sourceStart + content.length();

            if (!text.isEmpty() && sourceStart > previousSourceEnd) {
                text.append(' ');
            }
            int outStart = text.length();
            text.append(content);
            pieces.add(new NormalizedQuery.Piece(outStart, text.length(), sourceStart, sourceEnd, rewritten));
            previousSourceEnd = sourceEnd;
        }
        return new NormalizedQuery(raw, text.toString(), pieces);
    }

    private static int leadingSpace(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static Segment slice(String raw, int from, int to, Kind kind) {
        return new Segment(raw.substring(from, to), from, to, kind);
    }

    private static Segment sub(Segment segment, int from, int to, Kind kind) {
        return new Segment(segment.text().substring(from, to),
                segment.sourceStart() + from, segment.sourceStart() + to, kind);
    }

    private static String comparator(String phrase) {
        if (phrase == null) {
            // "stuck in Screening for two weeks" is a floor, not an equality: she wants
            // everyone who has been there that long and longer.
            return ">=";
        }
        return switch (collapse(phrase).toLowerCase(Locale.ROOT)) {
            case "more than", "longer than", "over" -> ">";
            case "less than", "under" -> "<";
            case "at most", "no more than" -> "<=";
            default -> ">=";
        };
    }

    /** "a week" and "2 weeks" both become days; months stay months, having no fixed length. */
    private static String duration(String phrase) {
        Matcher matcher = Pattern.compile("(" + NUMBER + ")\\s*(" + UNIT + ")", Pattern.CASE_INSENSITIVE)
                .matcher(phrase);
        if (!matcher.find()) {
            return phrase;
        }
        String number = lower(matcher.group(1));
        String unit = lower(matcher.group(2));
        int amount = NUMBER_WORDS.getOrDefault(number, 0);
        if (amount == 0) {
            amount = Integer.parseInt(number);
        }
        if (unit.startsWith("w")) {
            return amount * 7 + "d";
        }
        return unit.startsWith("m") ? amount + "mo" : amount + "d";
    }

    private static String date(String phrase) {
        String canonical = lower(collapse(phrase));
        return canonical.contains(" ") ? "\"" + canonical + "\"" : canonical;
    }

    private static String alternation(Stream<String> names) {
        return names.map(name -> name.toLowerCase(Locale.ROOT)).reduce((a, b) -> a + "|" + b).orElseThrow();
    }

    private static String collapse(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static Rewrite ci(String regex, Function<MatchResult, String> emit) {
        return new Rewrite(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), emit);
    }

    /**
     * Case matters to the name rule, and {@code CASE_INSENSITIVE} would quietly make
     * {@code \p{Lu}} match lowercase too, which would turn every pair of words into a name.
     */
    private static Rewrite cs(String regex, Function<MatchResult, String> emit) {
        return new Rewrite(Pattern.compile(regex), emit);
    }

    private Normalizer() {}
}
