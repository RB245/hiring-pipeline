package com.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateTest {

    private static Candidate sitting(Instant since) {
        return new Candidate(UUID.randomUUID(), Stage.SCREENING, since, Stage.SCREENING.bit());
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void timeInStageIsMeasuredAcrossADayBoundary() {
        Candidate candidate = sitting(Instant.parse("2025-03-10T22:45:00Z"));

        assertThat(candidate.timeInCurrentStage(at("2025-03-11T02:15:00Z")))
                .isEqualTo(Duration.ofHours(3).plusMinutes(30));
    }

    /**
     * The trap this exists to catch: two minutes either side of midnight is two minutes,
     * not one day. Anything reaching for calendar dates instead of elapsed time fails here.
     */
    @Test
    void crossingMidnightIsNotADay() {
        Candidate candidate = sitting(Instant.parse("2025-03-10T23:59:00Z"));

        assertThat(candidate.timeInCurrentStage(at("2025-03-11T00:01:00Z")))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(candidate.timeInCurrentStage(at("2025-03-11T00:01:00Z")).toDays()).isZero();
    }

    @Test
    void aWeekStuckInScreeningIsSevenDays() {
        Candidate candidate = sitting(Instant.parse("2025-03-04T09:00:00Z"));

        assertThat(candidate.timeInCurrentStage(at("2025-03-11T09:00:00Z")))
                .isEqualTo(Duration.ofDays(7));
    }
}
