package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import com.pipeline.search.Stages;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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

    /**
     * A bit test on the projection rather than a semi-join against the event log. Both
     * answer the question; V5 measured the mask at 8.4ms against 11.3ms for the EXISTS
     * pair, and the gap widens as histories grow because the mask is one row however many
     * events a candidate accumulates.
     */
    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        int bit = ((ResolvedValue.StageValue) value).stage().bit();
        return builder.isTrue(builder.function(
                "candidate_reached", Boolean.class, candidate.get("reachedMask"), builder.literal(bit)));
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
