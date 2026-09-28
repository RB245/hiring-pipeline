package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pipeline.search.fields.SearchFixture;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The abuse guards, each tested on both sides of its limit. */
class QueryGuardTest {

    private final SearchQueryParser parser = SearchFixture.parser();

    @Test
    void exactlyTheLengthLimitIsAllowed() {
        String query = "name:\"" + "a".repeat(QueryGuards.MAX_LENGTH - 7) + "\"";

        assertThat(query).hasSize(QueryGuards.MAX_LENGTH);
        assertThatCode(() -> parser.parse(query)).doesNotThrowAnyException();
    }

    @Test
    void oneCharacterOverIsRejected() {
        SearchQueryException thrown = parse("name:\"" + "a".repeat(QueryGuards.MAX_LENGTH - 6) + "\"");

        assertThat(thrown.code()).isEqualTo(ErrorCode.QUERY_TOO_LONG);
        assertThat(thrown).hasMessageContaining("513").hasMessageContaining("512");
    }

    @Test
    void exactlyTheConditionLimitIsAllowed() {
        assertThatCode(() -> parser.parse(repeat("stage:interview", QueryGuards.MAX_PREDICATES)))
                .doesNotThrowAnyException();
    }

    @Test
    void oneConditionOverIsRejected() {
        assertThat(parse(repeat("stage:interview", QueryGuards.MAX_PREDICATES + 1)).code())
                .isEqualTo(ErrorCode.TOO_MANY_PREDICATES);
    }

    @Test
    void bareTermsCountAgainstTheSameBudget() {
        assertThat(parse(repeat("sharma", QueryGuards.MAX_PREDICATES + 1)).code())
                .isEqualTo(ErrorCode.TOO_MANY_PREDICATES);
    }

    @Test
    void exactlyTheNestingLimitIsAllowed() {
        assertThatCode(() -> parser.parse(nested(QueryGuards.MAX_DEPTH))).doesNotThrowAnyException();
    }

    @Test
    void oneLevelDeeperIsRejected() {
        assertThat(parse(nested(QueryGuards.MAX_DEPTH + 1)).code()).isEqualTo(ErrorCode.TOO_DEEPLY_NESTED);
    }

    @Test
    void aWallOfNegationsTerminatesRatherThanExhaustingTheStack() {
        assertThat(parse("-".repeat(500)).code()).isEqualTo(ErrorCode.UNEXPECTED_TOKEN);
    }

    /**
     * Five thousand hostile strings. The claim being tested is not that any of them mean
     * anything, but that every one of them stops, and stops as a query error rather than
     * as a stack overflow or a pattern that never returns. A fixed seed so a failure can
     * be reproduced from the message alone.
     */
    @Test
    void randomInputAlwaysTerminatesAsAQueryErrorOrAParse() {
        Random random = new Random(20250928L);
        String alphabet = "abc 01:>=<()\"-,ORandnotexceptstage_interviewmoved_tosince7dw";

        for (int i = 0; i < 5000; i++) {
            StringBuilder query = new StringBuilder();
            for (int c = random.nextInt(QueryGuards.MAX_LENGTH + 8); c > 0; c--) {
                query.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            try {
                parser.parse(query.toString());
            } catch (SearchQueryException expected) {
                // A named reason is a pass: the point is that nothing else escapes.
            }
        }
    }

    private static String repeat(String condition, int times) {
        return (condition + " ").repeat(times).strip();
    }

    private static String nested(int depth) {
        return "(".repeat(depth) + "stage:offer" + ")".repeat(depth);
    }

    private SearchQueryException parse(String query) {
        return catchThrowableOfType(SearchQueryException.class, () -> parser.parse(query));
    }
}
