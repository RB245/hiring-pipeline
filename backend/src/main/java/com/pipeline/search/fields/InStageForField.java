package com.pipeline.search.fields;

import com.pipeline.search.Durations;
import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import java.time.Clock;
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

    @Override
    public String valueKind() {
        return "a length of time";
    }

    @Override
    public List<String> examples() {
        return List.of(">7d", ">2w", ">3mo");
    }
}
