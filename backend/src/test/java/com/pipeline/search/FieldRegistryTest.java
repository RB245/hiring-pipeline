package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipeline.search.fields.SearchFixture;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The registry, and the open/closed claim it exists to make good on. */
class FieldRegistryTest {

    @Test
    void everyFieldIsRegisteredUnderTheNameItAnswersTo() {
        assertThat(SearchFixture.registry().fields()).containsExactly(
                "applied", "before", "in_stage_for", "moved_to", "name", "name_like",
                "reached", "since", "source", "stage", "status");
    }

    @Test
    void twoHandlersClaimingOneFieldFailAtStartupRatherThanAtQueryTime() {
        assertThatThrownBy(() -> new FieldRegistry(List.of(new CohortField(), new CohortField())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cohort");
    }

    /**
     * The test file 10 will run, run early. A new searchable field is one class and its
     * registration: the lexer, the parser, the validator and the guards below have never
     * heard of "source" and do not need to.
     */
    @Test
    void anEntirelyNewFieldWorksWithoutTouchingAnythingElse() {
        List<FieldHandler> handlers = new ArrayList<>(SearchFixture.handlers());
        handlers.add(new CohortField());
        SearchQueryParser parser = new SearchQueryParser(new FieldRegistry(handlers), SearchFixture.CLOCK);

        SearchQuery query = parser.parse("stage:interview cohort:spring");

        assertThat(query.dsl()).isEqualTo("stage:interview cohort:spring");
        Node.And and = (Node.And) query.ast();
        assertThat(((Node.Predicate) and.children().get(1)).resolved())
                .isEqualTo(new ResolvedValue.TextValue("spring", ResolvedValue.TextValue.Match.NAME));
    }

    /**
     * The other half of the open/closed claim, now that a field also has to say how it
     * filters: only one handler may answer a bare word, and a second one claiming them is
     * a startup failure rather than a race decided by bean ordering.
     */
    @Test
    void twoHandlersClaimingBareTermsFailAtStartup() {
        assertThatThrownBy(() -> new FieldRegistry(List.of(new GreedyField("first"), new GreedyField("second"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bare terms");
    }

    @Test
    void anUnregisteredFieldIsRejectedWithTheListOfRealOnes() {
        assertThat(SearchFixture.registry().find("cohort")).isEmpty();
    }

    /**
     * Everything a new searchable field has to be, and nothing else: a name, how to read
     * its value, how to filter on it, and what to say when she leaves the value off.
     *
     * <p>Called "cohort" because it has to be a field that does not exist. It was "source"
     * until file 10 made source real, at which point this test started failing — not
     * wrongly, but because the registry correctly refused two handlers claiming one name.
     * A hypothetical field has to stay hypothetical to be worth anything.
     */
    private static class CohortField implements FieldHandler {

        @Override
        public String field() {
            return "cohort";
        }

        @Override
        public ResolvedValue resolve(Node.Value value, Operator operator, Clock clock) {
            return new ResolvedValue.TextValue(value.text(), ResolvedValue.TextValue.Match.NAME);
        }

        @Override
        public Predicate predicate(
                ResolvedValue value, Root<?> candidate, CriteriaQuery<?> query, CriteriaBuilder builder) {
            return builder.equal(candidate.get("source"), ((ResolvedValue.TextValue) value).text());
        }

        @Override
        public String valueKind() {
            return "a cohort";
        }

        @Override
        public List<String> examples() {
            return List.of("spring");
        }
    }

    /** Fields that both want bare words, which the registry must refuse to assemble. */
    private static final class GreedyField extends CohortField {

        private final String name;

        private GreedyField(String name) {
            this.name = name;
        }

        @Override
        public String field() {
            return name;
        }

        @Override
        public Optional<ResolvedValue> bareTerm(String text) {
            return Optional.of(new ResolvedValue.TextValue(text, ResolvedValue.TextValue.Match.NAME));
        }
    }
}
