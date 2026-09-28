package com.pipeline.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pipeline.domain.Stage;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of the formula, away from any database. What the terms mean is asserted
 * against real rows in {@code SearchExecutionTest}; what is here is the part that has to
 * hold whatever the rows are.
 */
class RankingTest {

    /**
     * Compared with a tolerance rather than exactly: the four constants do not sum to
     * exactly one in binary, so dividing through leaves the odd last bit adrift. That is
     * the renormalisation being honest about floating point, not a wrong weight.
     */
    @Test
    void theWeightsAreTheOnesWrittenDown() {
        Ranking.Weights weights = Ranking.Weights.forQuery(true);

        assertThat(weights.nameMatch()).isCloseTo(0.50, within(1e-9));
        assertThat(weights.specificity()).isCloseTo(0.20, within(1e-9));
        assertThat(weights.recency()).isCloseTo(0.20, within(1e-9));
        assertThat(weights.stagePriority()).isCloseTo(0.10, within(1e-9));
    }

    /**
     * The renormalisation, and the property that makes it safe: dropping the name term
     * rescales the others but does not reorder them, so a query without text ranks by the
     * same priorities a query with text would have used among equally-named candidates.
     */
    @Test
    void aQueryWithoutTextRescalesTheRemainingTermsWithoutReweightingThem() {
        Ranking.Weights with = Ranking.Weights.forQuery(true);
        Ranking.Weights without = Ranking.Weights.forQuery(false);

        assertThat(without.nameMatch()).isZero();
        assertThat(without.specificity()).isCloseTo(0.40, within(1e-9));
        assertThat(without.recency()).isCloseTo(0.40, within(1e-9));
        assertThat(without.stagePriority()).isCloseTo(0.20, within(1e-9));

        assertThat(without.recency() / without.stagePriority())
                .isCloseTo(with.recency() / with.stagePriority(), within(1e-9));
    }

    /** A perfect answer has to be able to score one, or the number is not a score. */
    @Test
    void theWeightsSumToOneEitherWay() {
        for (boolean hasText : new boolean[] {true, false}) {
            Ranking.Weights weights = Ranking.Weights.forQuery(hasText);
            assertThat(weights.nameMatch() + weights.specificity() + weights.recency() + weights.stagePriority())
                    .as("hasText=%s", hasText)
                    .isCloseTo(1.0, within(1e-9));
        }
    }

    @Test
    void furtherAlongThePipelineRanksHigher() {
        assertThat(Ranking.stagePriority(Stage.OFFER)).isGreaterThan(Ranking.stagePriority(Stage.INTERVIEW));
        assertThat(Ranking.stagePriority(Stage.INTERVIEW)).isGreaterThan(Ranking.stagePriority(Stage.SCREENING));
        assertThat(Ranking.stagePriority(Stage.SCREENING)).isGreaterThan(Ranking.stagePriority(Stage.APPLIED));
    }

    /**
     * The one place declaration order is the wrong answer. REJECTED is the last stage
     * declared, so an ordinal would make it the highest-priority stage there is and float
     * rejected candidates to the top of every search.
     */
    @Test
    void beingRejectedIsNotProgress() {
        assertThat(Ranking.stagePriority(Stage.REJECTED)).isZero();
        assertThat(Ranking.byPriority()).first().isEqualTo(Stage.HIRED);
        assertThat(Ranking.byPriority()).last().isEqualTo(Stage.REJECTED);
    }

    @Test
    void everyStageScoresWithinTheUnitRange() {
        for (Stage stage : Stage.values()) {
            assertThat(Ranking.stagePriority(stage)).as("%s", stage).isBetween(0.0, 1.0);
        }
    }
}
