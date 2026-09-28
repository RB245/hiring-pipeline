package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import java.time.Clock;
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
