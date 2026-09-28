package com.pipeline.infrastructure;

import java.time.Duration;

/**
 * Behind a port so the application runs without Redis. That is not hypothetical
 * flexibility: an in-process bucket is the right answer for a single instance on a
 * laptop, and a shared one is the right answer for more than one instance.
 */
public interface RateLimiterPort {

    Verdict tryConsume(String key, RateLimitTier tier);

    /**
     * @param resetAfter how long until the caller could succeed; zero when they just did
     */
    record Verdict(boolean allowed, long limit, long remaining, Duration resetAfter) {}
}
