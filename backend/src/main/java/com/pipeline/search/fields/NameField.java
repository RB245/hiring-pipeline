package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The candidate's name, matched fuzzily. Nothing is validated on the way in on purpose:
 * any string is a plausible name, and rejecting one because it looked odd would be the
 * parser deciding who exists.
 *
 * <p>This field also claims bare words, which is why it is the one place that knows a
 * person can be identified by something other than their name.
 */
@Component
class NameField implements FieldHandler {

    @Override
    public String field() {
        return "name";
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return new ResolvedValue.TextValue(value.text().strip(), false);
    }

    /**
     * A bare word means the same fuzzy name match, widened to the email. The widening is
     * the whole reason bare terms are a separate resolution rather than a rewrite into
     * {@code name:}: pasting an address into the search box has to find the person.
     */
    @Override
    public Optional<ResolvedValue> bareTerm(String text) {
        return Optional.of(new ResolvedValue.TextValue(text.strip(), true));
    }

    /**
     * Two arms, both index-backed, which is the only reason this is affordable. The name
     * arm is candidate_name_matches, a single-expression SQL function that Postgres
     * inlines so the %> operator stays visible and candidate_name_trgm_idx is still
     * chosen. The email arm is plain equality against the existing unique index.
     *
     * <p>The email comparison is wrapped in citext() rather than left to the driver. A
     * parameter bound as varchar makes Postgres resolve {@code citext = varchar} as an
     * ordinary text comparison, which is case-sensitive — so the column's whole point
     * would be quietly lost and "Priya@Example.com" would stop finding her. Measured, not
     * guessed: the uncast form returns zero rows for an address that exists.
     *
     * <p>Exact rather than fuzzy on the email arm, and that was measured too. Nobody
     * types a mangled fragment of an address hoping for a fuzzy hit; they paste the whole
     * thing. Making it fuzzy costs 310ms against 50k rows because the unindexed arm drags
     * the whole OR into a sequential scan, and a trigram index on the column does not
     * rescue it — email is citext, so LIKE is the citext operator and gin_trgm_ops never
     * applies. This form plans to a BitmapOr across two existing indexes at 24ms.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        ResolvedValue.TextValue text = (ResolvedValue.TextValue) value;
        Predicate byName = builder.isTrue(builder.function(
                "candidate_name_matches", Boolean.class, candidate.get("fullName"), builder.literal(text.text())));
        if (!text.alsoEmail()) {
            return byName;
        }
        // citext(?) is the function-call spelling of ?::citext.
        return builder.or(
                byName,
                builder.equal(
                        candidate.get("email"),
                        builder.function("citext", String.class, builder.literal(text.text()))));
    }

    @Override
    public String valueKind() {
        return "a name";
    }

    @Override
    public List<String> examples() {
        return List.of("\"priya sharma\"", "sharma");
    }
}
