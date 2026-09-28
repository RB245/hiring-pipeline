package com.pipeline.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "pipeline.rate-limit")
public record RateLimitProperties(
        /** "redis" for the shared bucket, anything else for the in-process one. */
        @DefaultValue("memory") String backend,
        @DefaultValue("20") int write,
        @DefaultValue("300") int read,
        @DefaultValue("60") int search,
        @DefaultValue("1m") Duration window,
        @DefaultValue("localhost") String redisHost,
        @DefaultValue("6379") int redisPort) {

    public int capacityFor(RateLimitTier tier) {
        return switch (tier) {
            case WRITE -> write;
            case READ -> read;
            case SEARCH -> search;
        };
    }
}
