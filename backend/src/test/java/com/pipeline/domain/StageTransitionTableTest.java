package com.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * All 36 ordered stage pairs, exhaustively. The expected set is written out by hand
 * rather than derived, because deriving it from Stage.next() would just restate the
 * implementation and agree with it however wrong it was.
 */
class StageTransitionTableTest {

    private static final Instant SINCE = Instant.parse("2025-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-02T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor ACTOR = new Actor("recruiter-1", "Asha");

    private static final Set<Map.Entry<Stage, Stage>> LEGAL = Set.of(
            Map.entry(Stage.APPLIED, Stage.SCREENING),
            Map.entry(Stage.APPLIED, Stage.REJECTED),
            Map.entry(Stage.SCREENING, Stage.INTERVIEW),
            Map.entry(Stage.SCREENING, Stage.REJECTED),
            Map.entry(Stage.INTERVIEW, Stage.OFFER),
            Map.entry(Stage.INTERVIEW, Stage.REJECTED),
            Map.entry(Stage.OFFER, Stage.HIRED),
            Map.entry(Stage.OFFER, Stage.REJECTED));

    private final StageTransitions transitions = new StageTransitions(TransitionRules.standard(), CLOCK);

    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (Stage from : Stage.values()) {
            for (Stage to : Stage.values()) {
                pairs.add(Arguments.of(from, to, LEGAL.contains(Map.entry(from, to))));
            }
        }
        return pairs.stream();
    }

    /** Guards the table itself: a shrinking parameter source would otherwise pass quietly. */
    @Test
    void theTableCoversEveryPair() {
        assertThat(allPairs()).hasSize(36);
        assertThat(LEGAL).hasSize(8);
    }

    @ParameterizedTest(name = "{0} -> {1} is legal: {2}")
    @MethodSource("allPairs")
    void everyPairIsAllowedOrRejected(Stage from, Stage to, boolean legal) {
        Candidate candidate = new Candidate(UUID.randomUUID(), from, SINCE, from.bit());

        if (legal) {
            assertThatCode(() -> transitions.transition(candidate, to, ACTOR, null, null))
                    .doesNotThrowAnyException();
            assertThat(transitions.transition(candidate, to, ACTOR, null, null).newStage())
                    .isEqualTo(to);
        } else {
            assertThatExceptionOfType(IllegalStageTransitionException.class)
                    .isThrownBy(() -> transitions.transition(candidate, to, ACTOR, null, null));
        }
    }
}
