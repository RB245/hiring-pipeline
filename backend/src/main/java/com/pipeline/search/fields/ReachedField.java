package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Whether the candidate was ever in that stage, whatever happened afterwards. Distinct
 * from {@code stage:} in exactly the case the recruiter cares about: someone who reached
 * Offer and was then rejected.
 */
@Component
class ReachedField implements FieldHandler {

    @Override
    public String field() {
        return "reached";
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return new ResolvedValue.StageValue(Stages.resolve(value));
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
