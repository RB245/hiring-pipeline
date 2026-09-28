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
import org.springframework.stereotype.Component;

/**
 * {@code name:}, forgiving a typo or two. What {@link NameField} points at when it finds
 * nobody, and the reason a zero-result suggestion is something she can act on rather than
 * only read.
 *
 * <p>Spelled as a field, next to the one it widens, so that {@code nam} in the search box
 * offers both and the feature teaches itself. The alternative spellings were worse for
 * reasons worth recording: {@code sounds_like:} promises phonetic matching, which this is
 * not — {@code Smyth} and {@code Smith} sound alike but are an edit apart, and the two
 * disagree often enough that the name would be a lie. {@code similar:} says nothing about
 * what is similar, which will matter the first time anything else about a candidate can be.
 *
 * <p>It is a first-class field, not a private hook for the suggestion machinery: she can
 * type it herself, it autocompletes, {@code /explain} shows it, and a query using it is a
 * query like any other. That is what keeps the suggestion honest, because the query the
 * suggestion offers is the query the count was taken from.
 */
@Component
class NameLikeField implements FieldHandler {

    @Override
    public String field() {
        return "name_like";
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return new ResolvedValue.TextValue(value.text().strip(), ResolvedValue.TextValue.Match.LOOSE);
    }

    /**
     * Deliberately the only slow field. candidate_name_typo cannot be indexed — no index
     * finds a transposition — and cannot be inlined either, so it costs a function call per
     * row: measured at 49ms over the 200 candidates a seeded pipeline holds, and 3.0s over
     * 50k, against 11ms for the indexed trigram arm.
     *
     * <p>That is why {@code name:} does not do this and why the zero-result retry runs under
     * a statement timeout. Typed directly by a recruiter it is unbounded, which is the right
     * way round: a query she chose to run is allowed to take its time, while one the system
     * ran on her behalf, on top of a search that already failed, is not.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return Names.loosely((ResolvedValue.TextValue) value, candidate, builder);
    }

    @Override
    public String valueKind() {
        return "a name, spelled approximately";
    }

    @Override
    public List<String> examples() {
        return List.of("sharma", "\"priya sharma\"");
    }
}
