package com.pipeline.search.fields;

import com.pipeline.search.Durations;
import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * How long the candidate has been where they are. The question behind the whole feature:
 * who is stuck.
 *
 * <p>The comparison is on the age, not on the timestamp, so {@code >7d} means the stage was
 * entered before the threshold. Inverting that is the easy mistake, and it would quietly
 * return the newest candidates in answer to "who is stuck".
 */
@Component
class InStageForField implements FieldHandler {

    @Override
    public String field() {
        return "in_stage_for";
    }

    @Override
    public Set<Operator> operators() {
        return Set.of(Operator.values());
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        // "in_stage_for:7d" with no comparison is a floor: nobody asks for a candidate
        // who has been in a stage for exactly seven days to the second.
        Operator effective = operator == Operator.EQUALS ? Operator.GREATER_OR_EQUAL : operator;
        return new ResolvedValue.AgeValue(effective, value.text(), Durations.threshold(value, clock));
    }

    /**
     * The "AND NOT is_terminal" is not decoration and not an optimisation bolted on
     * afterwards — it is half of what this field means. Time-in-stage is meaningless once
     * someone is hired or rejected: they are not stuck, they are finished.
     *
     * <p>It is also what makes candidate_active_since_idx reachable, and V5 is emphatic
     * about that for a measured reason. Re-measured here against 50k rows: with this
     * conjunct the planner takes the partial index and the cutoff is part of the index
     * condition (4.7ms); without it, it silently falls back to candidate_stage_idx and
     * re-checks the date as a filter, reading 8333 rows to keep 7777. Writing the same
     * thing as {@code current_stage NOT IN ('HIRED','REJECTED')} does not work either —
     * that was tried, and the planner's predicate prover cannot match it to the index.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return builder.and(
                builder.isFalse(candidate.get("isTerminal")),
                Ages.matching(
                        (ResolvedValue.AgeValue) value, candidate.<Instant>get("currentStageSince"), builder));
    }

    @Override
    public String valueKind() {
        return "a length of time";
    }

    @Override
    public List<String> examples() {
        return List.of(">7d", ">2w", ">3mo");
    }
}
