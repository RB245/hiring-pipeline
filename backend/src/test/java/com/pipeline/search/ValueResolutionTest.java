package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.search.fields.SearchFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Everything relative, resolved against the fixed clock. The clock is Wednesday
 * 2025-03-12 09:30 UTC, so each expectation below can be checked by counting days on a
 * calendar rather than by running the code.
 */
class ValueResolutionTest {

    private final SearchQueryParser parser = SearchFixture.parser();

    @ParameterizedTest
    @CsvSource({
        "today,           2025-03-12T00:00:00Z",
        "yesterday,       2025-03-11T00:00:00Z",
        "monday,          2025-03-10T00:00:00Z",
        "wednesday,       2025-03-12T00:00:00Z",
        "friday,          2025-03-07T00:00:00Z",
        "'last monday',   2025-03-03T00:00:00Z",
        "'this week',     2025-03-10T00:00:00Z",
        "'last week',     2025-03-03T00:00:00Z",
        "2025-01-09,      2025-01-09T00:00:00Z"
    })
    void datesResolveAgainstTheInjectedClock(String literal, Instant expected) {
        ResolvedValue.MovedToValue moved = movedTo("moved_to:offer since:\"" + literal + "\"");

        assertThat(moved.since()).hasValueSatisfying(date -> assertThat(date.instant()).isEqualTo(expected));
    }

    /**
     * Today is a Wednesday, so "wednesday" is today rather than a week ago. A recruiter
     * asking on Wednesday who moved since Wednesday means this morning.
     */
    @Test
    void aWeekdayThatIsTodayMeansToday() {
        assertThat(movedTo("moved_to:offer since:wednesday").since())
                .hasValueSatisfying(date ->
                        assertThat(date.instant()).isEqualTo(SearchFixture.NOW.truncatedTo(ChronoUnit.DAYS)));
    }

    @ParameterizedTest
    @CsvSource({
        "7d,   2025-03-05T09:30:00Z",
        "1d,   2025-03-11T09:30:00Z",
        "2w,   2025-02-26T09:30:00Z",
        "3mo,  2024-12-12T09:30:00Z",
        "1mo,  2025-02-12T09:30:00Z"
    })
    void durationsResolveToAnInstantRatherThanBeingCarriedAround(String literal, Instant expected) {
        assertThat(age("in_stage_for:>" + literal).threshold()).isEqualTo(expected);
    }

    /**
     * The comparison is on the age, not on the timestamp. Getting this backwards would
     * answer "who is stuck" with the people who arrived this morning, and every row would
     * still look plausible.
     */
    @Test
    void aGreaterThanOnAnAgeMeansOlderThanTheThreshold() {
        ResolvedValue.AgeValue stuck = age("in_stage_for:>7d");

        assertThat(stuck.operator()).isEqualTo(Operator.GREATER_THAN);
        assertThat(stuck.threshold()).isBefore(SearchFixture.NOW);
    }

    @Test
    void anAgeWithNoComparisonIsAFloor() {
        assertThat(age("in_stage_for:7d").operator()).isEqualTo(Operator.GREATER_OR_EQUAL);
    }

    @Test
    void anApplicationAgeWithNoComparisonIsACeiling() {
        assertThat(age("applied:30d").operator()).isEqualTo(Operator.LESS_OR_EQUAL);
    }

    @Test
    void bothModifiersFoldIntoTheMoveTheyBound() {
        ResolvedValue.MovedToValue moved = movedTo("moved_to:offer since:monday before:today");

        assertThat(moved.since()).hasValueSatisfying(date -> assertThat(date.literal()).isEqualTo("monday"));
        assertThat(moved.before()).hasValueSatisfying(date -> assertThat(date.literal()).isEqualTo("today"));
    }

    /**
     * A modifier binds inside its own conjunction. Letting it reach across an OR would
     * date-bound a branch the recruiter never bounded.
     */
    @Test
    void aModifierDoesNotReachAcrossADisjunction() {
        Node.Or or = (Node.Or) parser.parse("moved_to:offer since:monday OR moved_to:hired").ast();

        assertThat(resolvedOf(or.children().get(0), ResolvedValue.MovedToValue.class).since()).isPresent();
        assertThat(resolvedOf(or.children().get(1), ResolvedValue.MovedToValue.class).since()).isEmpty();
    }

    private ResolvedValue.MovedToValue movedTo(String query) {
        return resolvedOf(parser.parse(query).ast(), ResolvedValue.MovedToValue.class);
    }

    private ResolvedValue.AgeValue age(String query) {
        return resolvedOf(parser.parse(query).ast(), ResolvedValue.AgeValue.class);
    }

    private static <T extends ResolvedValue> T resolvedOf(Node node, Class<T> type) {
        return type.cast(((Node.Predicate) node).resolved());
    }
}
