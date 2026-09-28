package com.pipeline.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.infrastructure.RateLimitConfig;
import com.pipeline.infrastructure.RateLimitProperties;
import com.pipeline.infrastructure.RateLimitTier;
import com.pipeline.infrastructure.RateLimiterPort;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;

/**
 * Both adapters, the same assertions. The in-memory one is what runs on a laptop and
 * the Redis one is what runs in production, so testing only the convenient one would
 * leave the shipped path unexercised.
 */
class RateLimiterTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    static Stream<Object[]> limiters() {
        RateLimitProperties memory =
                new RateLimitProperties("memory", 3, 5, 4, Duration.ofMinutes(1), "localhost", 6379);
        RateLimitProperties redis = new RateLimitProperties(
                "redis", 3, 5, 4, Duration.ofMinutes(1), REDIS.getHost(), REDIS.getFirstMappedPort());

        RateLimitConfig config = new RateLimitConfig();
        RedisClient client = config.redisClient(redis);

        return Stream.of(
                new Object[] {"caffeine", config.rateLimiter(memory, client)},
                new Object[] {"redis", config.rateLimiter(redis, client)});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void allowsUpToTheCapacityThenRefuses(String name, RateLimiterPort limiter) {
        String key = UUID.randomUUID().toString();

        for (int i = 1; i <= 3; i++) {
            RateLimiterPort.Verdict verdict = limiter.tryConsume(key, RateLimitTier.WRITE);
            assertThat(verdict.allowed()).as("request %d", i).isTrue();
            assertThat(verdict.limit()).isEqualTo(3);
            assertThat(verdict.remaining()).isEqualTo(3 - i);
        }

        RateLimiterPort.Verdict refused = limiter.tryConsume(key, RateLimitTier.WRITE);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.remaining()).isZero();
        assertThat(refused.resetAfter()).isPositive();
    }

    /** One noisy caller must not spend anybody else's budget. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void bucketsAreSeparatePerKey(String name, RateLimiterPort limiter) {
        String noisy = UUID.randomUUID().toString();
        String quiet = UUID.randomUUID().toString();

        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(noisy, RateLimitTier.WRITE);
        }

        assertThat(limiter.tryConsume(noisy, RateLimitTier.WRITE).allowed()).isFalse();
        assertThat(limiter.tryConsume(quiet, RateLimitTier.WRITE).allowed()).isTrue();
    }

    /** And exhausting one tier must not exhaust another for the same caller. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void bucketsAreSeparatePerTier(String name, RateLimiterPort limiter) {
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(key, RateLimitTier.WRITE);
        }

        assertThat(limiter.tryConsume(key, RateLimitTier.WRITE).allowed()).isFalse();
        assertThat(limiter.tryConsume(key, RateLimitTier.READ).allowed()).isTrue();
        assertThat(limiter.tryConsume(key, RateLimitTier.SEARCH).limit()).isEqualTo(4);
    }
}
