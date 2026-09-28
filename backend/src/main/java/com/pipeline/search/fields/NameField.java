package com.pipeline.search.fields;

import com.pipeline.search.FieldHandler;
import com.pipeline.search.Node;
import com.pipeline.search.Operator;
import com.pipeline.search.ResolvedValue;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The candidate's name, matched fuzzily in file 08. Nothing is validated here on purpose:
 * any string is a plausible name, and rejecting one because it looked odd would be the
 * parser deciding who exists.
 */
@Component
class NameField implements FieldHandler {

    @Override
    public String field() {
        return "name";
    }

    @Override
    public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
        return new ResolvedValue.TextValue(value.text().strip());
    }

    @Override
    public String valueKind() {
        return "a name";
    }

    @Override
    public List<String> examples() {
        return List.of("\"priya sharma\"", "sharma");
    }
}
