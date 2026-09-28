package com.pipeline.search;

import java.util.Comparator;
import java.util.List;

/**
 * Edit distance, for turning {@code Intervew} back into {@code Interview}. Hand-written
 * rather than pulled in with a library: it is twenty lines, and the alternative is a
 * dependency whose only caller is this one.
 */
final class Levenshtein {

    /**
     * Two edits, which catches a transposition, a dropped letter and a doubled letter but
     * will not claim {@code offer} was meant to be {@code hired}. Short words get one, so
     * that a three-letter typo does not match half the vocabulary.
     */
    private static int budgetFor(String typed) {
        return typed.length() <= 4 ? 1 : 2;
    }

    /** Candidates within the budget, closest first, ties broken alphabetically for stability. */
    static List<String> closest(String typed, List<String> candidates) {
        int budget = budgetFor(typed);
        return candidates.stream()
                .filter(candidate -> distance(typed.toLowerCase(), candidate.toLowerCase()) <= budget)
                .sorted(Comparator
                        .comparingInt((String candidate) -> distance(typed.toLowerCase(), candidate.toLowerCase()))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private Levenshtein() {}
}
