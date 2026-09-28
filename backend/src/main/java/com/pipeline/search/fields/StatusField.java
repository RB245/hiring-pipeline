package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Statuses;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/** Outcome rather than position: hired, rejected, or still in play. */
@Component
class StatusField implements FieldHandler {

    @Override
    public String field() {
        return "status";
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return new ResolvedValue.StatusValue(Statuses.resolve(value));
    }

    @Override
    public String valueKind() {
        return "one of " + String.join(", ", Statuses.names());
    }

    @Override
    public List<String> examples() {
        return Statuses.names();
    }
}
