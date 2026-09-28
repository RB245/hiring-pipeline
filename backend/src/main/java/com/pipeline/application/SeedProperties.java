package com.pipeline.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "pipeline.seed")
public record SeedProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("200") int candidates,
        /** Blank means "today", which keeps the recency guarantees true. Pin to reproduce exactly. */
        @DefaultValue("") String baseInstant) {}
