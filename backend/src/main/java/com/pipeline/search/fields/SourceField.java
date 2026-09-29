package com.pipeline.search.fields;

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
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Where the candidate came from: the source column that has been on the table since file
 * 02 and that nothing has asked about until now.
 *
 * <p>Exact rather than fuzzy, and that is the whole difference from {@code name:}. A source
 * is a label chosen from a short list by whoever entered it, not a person's name typed from
 * memory, so there is nothing to be forgiving about: "referral" is either what the row says
 * or it is not. Folded to lower case because the label is the value, not its capitalisation.
 */
@Component
class SourceField implements FieldHandler {

    @Override
    public String field() {
        return "source";
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return new ResolvedValue.ExactValue(value.text().strip().toLowerCase(Locale.ROOT));
    }

    @Override
    public Predicate predicate(
            ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
        return builder.equal(candidate.get("source"), ((ResolvedValue.ExactValue) value).text());
    }

    @Override
    public String valueKind() {
        return "a source";
    }

    /**
     * The ones the seed uses. Deliberately examples rather than a vocabulary: the column is
     * free text, so a registry that refused an unlisted source would reject real data.
     */
    @Override
    public List<String> examples() {
        return List.of("referral", "linkedin", "careers-page", "agency");
    }
}
