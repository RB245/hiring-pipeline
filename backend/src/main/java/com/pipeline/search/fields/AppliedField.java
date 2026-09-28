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
 * Age of the application itself. Same convention as {@code in_stage_for}: the comparison
 * is on how old it is, so {@code applied:<30d} is everyone who applied within the last
 * month and {@code applied:>1mo} is everyone still waiting from before that.
 */
@Component
class AppliedField implements FieldHandler {

    @Override
    public String field() {
        return "applied";
    }

    @Override
    public Set<Operator> operators() {
        return Set.of(Operator.values());
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        Operator effective = operator == Operator.EQUALS ? Operator.LESS_OR_EQUAL : operator;
        return new ResolvedValue.AgeValue(effective, value.text(), Durations.threshold(value, clock));
    }

    @Override
    public String valueKind() {
        return "a length of time";
    }

    @Override
    public List<String> examples() {
        return List.of("<30d", "<7d", ">3mo");
    }
}
