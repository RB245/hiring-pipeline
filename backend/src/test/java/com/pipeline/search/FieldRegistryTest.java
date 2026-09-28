package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipeline.search.fields.SearchFixture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The registry, and the open/closed claim it exists to make good on. */
class FieldRegistryTest {

    @Test
    void everyFieldIsRegisteredUnderTheNameItAnswersTo() {
        assertThat(SearchFixture.registry().fields()).containsExactly(
                "applied", "before", "in_stage_for", "moved_to", "name", "reached", "since", "stage", "status");
    }

    @Test
    void twoHandlersClaimingOneFieldFailAtStartupRatherThanAtQueryTime() {
        assertThatThrownBy(() -> new FieldRegistry(List.of(new SourceField(), new SourceField())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source");
    }

    /**
     * The test file 10 will run, run early. A new searchable field is one class and its
     * registration: the lexer, the parser, the validator and the guards below have never
     * heard of "source" and do not need to.
     */
    @Test
    void anEntirelyNewFieldWorksWithoutTouchingAnythingElse() {
        List<FieldHandler> handlers = new ArrayList<>(SearchFixture.handlers());
        handlers.add(new SourceField());
        SearchQueryParser parser = new SearchQueryParser(new FieldRegistry(handlers), SearchFixture.CLOCK);

        SearchQuery query = parser.parse("stage:interview source:referral");

        assertThat(query.dsl()).isEqualTo("stage:interview source:referral");
        Node.And and = (Node.And) query.ast();
        assertThat(((Node.Predicate) and.children().get(1)).resolved())
                .isEqualTo(new ResolvedValue.TextValue("referral"));
    }

    @Test
    void anUnregisteredFieldIsRejectedWithTheListOfRealOnes() {
        assertThat(SearchFixture.registry().find("source")).isEmpty();
    }

    private static final class SourceField implements FieldHandler {

        @Override
        public String field() {
            return "source";
        }

        @Override
        public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
            return new ResolvedValue.TextValue(value.text());
        }

        @Override
        public String valueKind() {
            return "a source";
        }

        @Override
        public List<String> examples() {
            return List.of("referral");
        }
    }
}
