package com.pipeline.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Autocomplete: what she could type next, given what she has typed so far.
 *
 * <p>Deliberately not built on the parser. Everything reaching this is half-written by
 * definition — {@code stage:in} is a query the validator would reject — so running it
 * through a pipeline whose job is to reject invalid queries would mean suggesting nothing
 * exactly when suggestions are wanted. It reads the last word instead, which is all a
 * completion needs to know.
 *
 * <p>It still knows no field by name. The list of fields and the values each one accepts
 * both come from the registry, so a new searchable field autocompletes without anyone
 * remembering to add it here.
 */
@Component
public class Suggester {

    /** One completion: the token to put in place of what she was typing. */
    public record Completion(String value, String label, Kind kind) {}

    public enum Kind {
        FIELD,
        VALUE
    }

    /** {@code replacing} is the span of the token being completed, so the UI can splice. */
    public record Suggestions(Span replacing, List<Completion> completions) {}

    private final FieldRegistry registry;

    public Suggester(FieldRegistry registry) {
        this.registry = registry;
    }

    public Suggestions suggest(String query) {
        String text = query == null ? "" : query;
        int start = tokenStart(text);
        String token = text.substring(start);
        Span replacing = new Span(start, text.length());

        // A leading "-" is negation, not part of what is being completed, but it has to
        // come back on the front of every completion or accepting one would drop it.
        String sign = token.startsWith("-") ? "-" : "";
        String bare = token.substring(sign.length());

        int colon = bare.indexOf(':');
        return new Suggestions(
                replacing,
                colon < 0
                        ? fields(sign, bare)
                        : values(sign, bare.substring(0, colon), bare.substring(colon + 1)));
    }

    private List<Completion> fields(String sign, String partial) {
        String lower = partial.toLowerCase(Locale.ROOT);
        List<Completion> completions = new ArrayList<>();
        for (String field : registry.fields()) {
            if (field.startsWith(lower)) {
                completions.add(new Completion(sign + field + ":", field, Kind.FIELD));
            }
        }
        return List.copyOf(completions);
    }

    /**
     * The field's own examples are its vocabulary. For the closed ones — stage, status —
     * that is the complete list of legal values; for an open one such as {@code name} it
     * is a handful of shapes, which is the honest thing to offer when there is nothing to
     * enumerate.
     */
    private List<Completion> values(String sign, String field, String partial) {
        String lower = partial.toLowerCase(Locale.ROOT);
        return registry.find(field)
                .map(handler -> handler.examples().stream()
                        .filter(example -> example.toLowerCase(Locale.ROOT).startsWith(lower))
                        .map(example -> new Completion(sign + field + ":" + example, example, Kind.VALUE))
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Where the word she is in the middle of began. An unclosed quote holds a word
     * together across the space inside it, so {@code name:"priya sh} completes as one
     * token rather than as a stray {@code sh}.
     */
    private static int tokenStart(String text) {
        boolean insideQuote = text.chars().filter(character -> character == '"').count() % 2 == 1;
        int from = insideQuote ? text.lastIndexOf('"') : text.length();
        for (int i = from - 1; i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        return 0;
    }
}
