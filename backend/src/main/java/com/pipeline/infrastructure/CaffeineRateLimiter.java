package com.pipeline.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;

/**
 * In-process buckets, for running without Redis. Correct for a single instance and
 * wrong for several, which is exactly the trade the backend property exists to make.
 */
class CaffeineRateLimiter implements RateLimiterPort {

    private final RateLimitProperties properties;
    private final Cache<String, Bucket> buckets;

    CaffeineRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
        // Evicted well after a bucket could still be exhausted, so an idle caller is
        // not remembered forever and an active one is never silently reset.
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(properties.window().multipliedBy(2))
                .maximumSize(100_000)
                .build();
    }

    @Override
    public Verdict tryConsume(String key, RateLimitTier tier) {
        int capacity = properties.capacityFor(tier);
        Bucket bucket = buckets.get(tier + "|" + key, ignored -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, properties.window())
                        .build())
                .build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return new Verdict(
                probe.isConsumed(),
                capacity,
                probe.getRemainingTokens(),
                Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
}
