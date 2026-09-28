package com.pipeline.infrastructure;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The shared bucket. Every instance consumes from the same counter, which is the only
 * version of this that means anything once there is more than one instance.
 */
class LettuceRateLimiter implements RateLimiterPort {

    private final RateLimitProperties properties;
    private final LettuceBasedProxyManager<byte[]> proxyManager;

    LettuceRateLimiter(RateLimitProperties properties, RedisClient client) {
        this.properties = properties;
        StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
        this.proxyManager = LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                        properties.window().multipliedBy(2)))
                .build();
    }

    @Override
    public Verdict tryConsume(String key, RateLimitTier tier) {
        int capacity = properties.capacityFor(tier);
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, properties.window())
                        .build())
                .build();

        BucketProxy bucket = proxyManager
                .builder()
                .build((tier + "|" + key).getBytes(StandardCharsets.UTF_8), () -> configuration);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return new Verdict(
                probe.isConsumed(),
                capacity,
                probe.getRemainingTokens(),
                Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
}
