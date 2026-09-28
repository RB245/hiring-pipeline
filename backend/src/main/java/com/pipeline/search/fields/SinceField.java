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

/** The lower bound on a move. Means nothing alone; {@link MovedToField} claims it. */
@Component
class SinceField implements FieldHandler {

    @Override
    public String field() {
        return "since";
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return Dates.resolve(value, clock);
    }

    /**
     * Never called. The validator folds a modifier into the field it modifies and rejects
     * one that has nothing to modify, so no modifier survives into the tree the builder
     * walks. Throwing says that out loud; returning something harmless would turn a
     * broken validator into a filter that quietly matched everyone.
     */
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
        return List.of("monday", "today", "2025-03-11");
    }
}
