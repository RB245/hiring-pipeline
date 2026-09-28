package com.pipeline.search.fields;

import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * How a person is matched by what she is called, in the one place both fields that do it
 * can share.
 *
 * <p>Shared rather than duplicated because {@code name_like:} is defined as "what
 * {@code name:} does, plus typos", and the moment those are two pieces of code that is a
 * claim rather than a fact. The zero-result retry counts rows with one of these and offers
 * a query that runs the other, so a difference between them would show up as a suggestion
 * promising a number it does not deliver.
 */
final class Names {

    /**
     * Fuzzily by name, and — for everything except a question explicitly about the name —
     * exactly by email.
     *
     * <p>candidate_name_matches is a single-expression SQL function, so Postgres inlines it
     * and the %> operator stays visible to the planner: this still reaches
     * candidate_name_trgm_idx. The email arm is plain equality against the existing unique
     * index, and the pair plans as a BitmapOr across the two.
     *
     * <p>The email comparison is wrapped in citext() rather than left to the driver. A
     * parameter bound as varchar makes Postgres resolve {@code citext = varchar} as an
     * ordinary text comparison, which is case-sensitive — so the column's whole point would
     * be quietly lost and "Priya@Example.com" would stop finding her. Measured, not guessed:
     * the uncast form returns zero rows for an address that exists.
     */
    static Predicate matching(ResolvedValue.TextValue text, Root<?> candidate, CriteriaBuilder builder) {
        Predicate byName = builder.isTrue(builder.function(
                "candidate_name_matches", Boolean.class, candidate.get("fullName"), builder.literal(text.text())));
        if (!text.includesEmail()) {
            return byName;
        }
        // citext(?) is the function-call spelling of ?::citext.
        return builder.or(byName, builder.equal(candidate.get("email"), citext(text.text(), builder)));
    }

    /**
     * The same, widened by edit distance.
     *
     * <p>This is the half of fuzzy matching the normal filter cannot afford. A transposition
     * is invisible to trigrams — word_similarity('pryia', 'Priya Sharma') is 0.333, under
     * any threshold that would not also let in noise — so "pryia" survives neither the %>
     * filter nor, consequently, the typo floor in the score, which only ever sees rows the
     * filter already returned.
     *
     * <p>Or-ed with the tight predicate rather than replacing it, so this is a genuine
     * superset: a name trigrams match at 0.6 but edit distance rejects still counts.
     */
    static Predicate loosely(ResolvedValue.TextValue text, Root<?> candidate, CriteriaBuilder builder) {
        return builder.or(
                matching(text, candidate, builder),
                builder.isTrue(builder.function(
                        "candidate_name_typo",
                        Boolean.class,
                        candidate.get("fullName"),
                        builder.literal(text.text()))));
    }

    private static Expression<String> citext(String term, CriteriaBuilder builder) {
        return builder.function("citext", String.class, builder.literal(term));
    }

    private Names() {}
}
