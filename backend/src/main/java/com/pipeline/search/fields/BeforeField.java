package com.pipeline.search.fields;

import com.pipeline.search.Dates;
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

/** The upper bound on a move. The other half of {@link SinceField}. */
@Component
class BeforeField implements FieldHandler {

    @Override
    public String field() {
        return "before";
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return Dates.resolve(value, clock);
    }

    /** Never called, for the reason given on {@link SinceField#predicate}. */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        throw new IllegalStateException(field() + ": is a modifier and has no predicate of its own");
    }

    @Override
    public String valueKind() {
        return "a date";
    }

    @Override
    public List<String> examples() {
        return List.of("today", "monday", "2025-03-11");
    }
}
