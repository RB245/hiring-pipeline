package com.pipeline.search.fields;

import com.pipeline.infrastructure.StageEventEntity;
import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A move that happened, rather than a state the candidate is in: an event with that
 * destination. The only field with modifiers, because a move is the only thing in the
 * pipeline that has a time worth bounding.
 */
@Component
class MovedToField implements FieldHandler {

    @Override
    public String field() {
        return "moved_to";
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return new ResolvedValue.MovedToValue(Stages.resolve(value), Optional.empty(), Optional.empty());
    }

    /**
     * The only field that reads the log instead of the projection, and it has to: the
     * projection knows where somebody is now, not that they passed through Interview on
     * Tuesday and were rejected on Thursday. EXISTS rather than a join so that a candidate
     * with three qualifying events is still one row.
     *
     * <p>The bounds are half-open. A date resolves to midnight, so {@code since:monday}
     * includes everything from Monday morning and {@code before:today} stops at last
     * midnight — which is what "before today" means to the person typing it.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        ResolvedValue.MovedToValue moved = (ResolvedValue.MovedToValue) value;

        Subquery<Integer> events = query.subquery(Integer.class);
        Root<StageEventEntity> event = events.from(StageEventEntity.class);

        List<Predicate> conditions = new ArrayList<>();
        conditions.add(builder.equal(event.get("candidateId"), candidate.get("id")));
        conditions.add(builder.equal(event.get("toStage"), moved.stage()));
        moved.since().ifPresent(since ->
                conditions.add(builder.greaterThanOrEqualTo(event.<Instant>get("occurredAt"), since.instant())));
        moved.before().ifPresent(before ->
                conditions.add(builder.lessThan(event.<Instant>get("occurredAt"), before.instant())));

        return builder.exists(events.select(builder.literal(1)).where(conditions.toArray(Predicate[]::new)));
    }

    @Override
    public Set<String> modifiers() {
        return Set.of("since", "before");
    }

    @Override
    public ResolvedValue attach(ResolvedValue resolved, Map<String, ResolvedValue> modifiers) {
        ResolvedValue.MovedToValue moved = (ResolvedValue.MovedToValue) resolved;
        return new ResolvedValue.MovedToValue(moved.stage(), bound(modifiers, "since"), bound(modifiers, "before"));
    }

    private static Optional<ResolvedValue.DateValue> bound(Map<String, ResolvedValue> modifiers, String name) {
        return Optional.ofNullable(modifiers.get(name)).map(ResolvedValue.DateValue.class::cast);
    }

    @Override
    public String valueKind() {
        return "a stage";
    }

    @Override
    public List<String> examples() {
        return Stages.names();
    }
}
