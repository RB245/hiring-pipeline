package com.pipeline.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

class ClockConfigTest {

    @Test
    void productionClockIsUtc() {
        assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @SpringBootTest(classes = ClockConfig.class)
    @Import(FixedClockConfig.class)
    static class FixedClockOverridesProductionClock {

        @Autowired
        Clock clock;

        @Test
        void injectedClockIsFrozen() {
            assertThat(clock.instant()).isEqualTo(FixedClockConfig.FIXED_INSTANT);
        }
    }
}
