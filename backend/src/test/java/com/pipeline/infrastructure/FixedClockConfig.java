package com.pipeline.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Import this in any test that needs time to stand still.
 */
@TestConfiguration
public class FixedClockConfig {

    public static final Instant FIXED_INSTANT = Instant.parse("2025-01-01T00:00:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }
}
