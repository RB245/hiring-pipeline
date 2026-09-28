package com.pipeline.infrastructure;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(RateLimitProperties properties) {
        return RedisClient.create(RedisURI.create(properties.redisHost(), properties.redisPort()));
    }

    /**
     * Chosen by configuration rather than by what happens to be on the classpath, so
     * which limiter is running is a deployment decision someone made on purpose.
     */
    @Bean
    public RateLimiterPort rateLimiter(RateLimitProperties properties, RedisClient redisClient) {
        return "redis".equalsIgnoreCase(properties.backend())
                ? new LettuceRateLimiter(properties, redisClient)
                : new CaffeineRateLimiter(properties);
    }
}
