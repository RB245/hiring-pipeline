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
        return new ResolvedValue.TextValue(value.text().strip(), ResolvedValue.TextValue.Match.NAME);
    }

    /**
     * A bare word means the same fuzzy name match, widened to the email. The widening is
     * the whole reason bare terms are a separate resolution rather than a rewrite into
     * {@code name:}: pasting an address into the search box has to find the person.
     */
    @Override
    public Optional<ResolvedValue> bareTerm(String text) {
        return Optional.of(new ResolvedValue.TextValue(text.strip(), ResolvedValue.TextValue.Match.IDENTITY));
    }

    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return Names.matching((ResolvedValue.TextValue) value, candidate, builder);
    }

    /**
     * When this finds nobody, {@code name_like:} is the question worth asking instead.
     *
     * <p>A field rather than a flag on the request, and that is the point rather than an
     * implementation detail. A flag would put part of the question outside the query
     * string, so the search box would stop describing its own results, the interpretation
     * chips would have nothing to show for it, and {@code /explain} would stop being the
     * whole truth about what was run. As a field it costs one more handler and touches
     * neither the lexer nor the parser, so the claim that a new searchable field is one new
     * class survives it.
     */
    @Override
    public Optional<String> loosensTo() {
        return Optional.of("name_like");
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
