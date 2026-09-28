package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Statuses;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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

    /**
     * Derived from the stage rather than stored, exactly as {@link com.pipeline.search.Status}
     * derives it: an outcome is its terminal stage, and active is the absence of one.
     * Active reads the generated is_terminal column rather than writing out
     * {@code NOT IN ('HIRED','REJECTED')} — V4 says why the column exists, and the
     * difference is measurable wherever a partial index is involved.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return ((ResolvedValue.StatusValue) value)
                .status()
                .terminalStage()
                .map(stage -> builder.equal(candidate.get("currentStage"), stage))
                .orElseGet(() -> builder.isFalse(candidate.get("isTerminal")));
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
