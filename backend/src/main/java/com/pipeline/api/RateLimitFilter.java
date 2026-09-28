package com.pipeline.api;

import com.pipeline.infrastructure.RateLimitTier;
import com.pipeline.infrastructure.RateLimiterPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cross-cutting on purpose: no controller knows this exists, and none should.
 *
 * <p>Ordered ahead of Spring Security so an unauthenticated flood is throttled rather
 * than allowed to hammer the authentication path. That is why the key comes from the
 * header directly instead of from a principal that has not been resolved yet.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterPort limiter;
    private final Problems problems;
    private final MeterRegistry meters;

    RateLimitFilter(RateLimiterPort limiter, Problems problems, MeterRegistry meters) {
        this.limiter = limiter;
        this.problems = problems;
        this.meters = meters;
    }

    /** Health, metrics and the docs are not the recruiter's traffic and are not tiered. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        RateLimitTier tier = tierOf(request);
        RateLimiterPort.Verdict verdict = limiter.tryConsume(keyOf(request), tier);

        // On every response, not only on 429: a client that only learns the limit by
        // breaching it has no way to back off before breaching it.
        response.setHeader("X-RateLimit-Limit", Long.toString(verdict.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(0, verdict.remaining())));
        response.setHeader("X-RateLimit-Reset", Long.toString(verdict.resetAfter().toSeconds()));

        if (verdict.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        Counter.builder("pipeline.rate_limit.rejections")
                .description("Requests refused by the rate limiter")
                .tag("tier", tier.name().toLowerCase())
                .register(meters)
                .increment();

        // Rounded up, because a Retry-After of 0 invites an immediate retry that is
        // certain to fail again.
        long retryAfter = Math.max(1, (long) Math.ceil(verdict.resetAfter().toMillis() / 1000.0));
        response.setHeader("Retry-After", Long.toString(retryAfter));
        problems.write(
                response,
                problems.of(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "rate-limited",
                        "Too many requests",
                        "Limit of %d per window exceeded for %s requests. Retry in %ds."
                                .formatted(verdict.limit(), tier.name().toLowerCase(), retryAfter),
                        request));
    }

    private static RateLimitTier tierOf(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method) ? RateLimitTier.READ : RateLimitTier.WRITE;
    }

    /** By key where there is one, by address otherwise, so an anonymous caller is still bounded. */
    private static String keyOf(HttpServletRequest request) {
        String apiKey = request.getHeader(ApiKeyAuthFilter.HEADER);
        return (apiKey == null || apiKey.isBlank()) ? "ip:" + request.getRemoteAddr() : "key:" + apiKey;
    }
}
