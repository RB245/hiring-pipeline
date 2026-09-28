# 06 - Rate limiting, authenticated actor, observability, seed data

Prompt given: `prompts/06-*.md`

---

<!-- Let me check the two dependency questions that could reshape this before I plan.

  Ran 1 shell command

Both viable. Checking the exact Redis module and matching versions:

  Ran 1 shell command

Both confirmed. bucket4j_jdk17-lettuce:8.14.0 (Lettuce comes transitively, so I don't need Spring Data Redis at all) and Boot 3.4's StructuredLoggingJsonMembersCustomizer for masking.

Plan

┌─────┬──────────────────────────────────────────────┬───────────────────────────────────────────────────┐
│  #  │                     Step                     │                    Verified by                    │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│     │ Deps: bucket4j core+lettuce 8.14.0,          │                                                   │
│ 1   │ Caffeine, spring-boot-starter-security,      │ compiles                                          │
│     │ Testcontainers Redis                         │                                                   │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 2   │ V8__job.sql inserts the single job —         │ compose on an empty volume has exactly one job;   │
│     │ migrator identity, honouring the carry-over  │ tests drop ensureCanonicalJob()                   │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│     │ Auth: API-key filter → RecruiterPrincipal;   │ the event row's actor_id/actor_name match the     │
│ 3   │ CurrentActor reads the SecurityContext       │ configured recruiter, read back by raw SQL; no    │
│     │                                              │ key → 401                                         │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│     │                                              │ burst past 20 writes → 429 with all four headers  │
│ 4   │ RateLimiterPort + Caffeine and Lettuce       │ and the file-05 Problem shape; reads survive 300; │
│     │ adapters + tiers                             │  Redis adapter tested against a real Redis        │
│     │                                              │ container                                         │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│     │ ECS JSON logs, correlation ID in MDC, email  │ capture stdout, parse as JSON, assert             │
│ 5   │ masking                                      │ correlationId equals the response header and that │
│     │                                              │  an email is masked                               │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 6   │ Metrics: 2 counters                          │ /actuator/prometheus contains both, tagged        │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 7   │ Seeder: deterministic, idempotent,           │ all five guarantees asserted; re-running adds     │
│     │ backdated, through the app role              │ zero rows                                         │
├─────┼──────────────────────────────────────────────┼───────────────────────────────────────────────────┤
│ 8   │ Config: .env.example, compose,               │ compose on empty volume → populated DB; the 7-day │
│     │ application.yml, all env-driven              │  SQL returns ≥8                                   │
└─────┴──────────────────────────────────────────────┴───────────────────────────────────────────────────┘

Pushback before I start

I'm not adding a request-latency timer. Actuator + Micrometer already publish http_server_requests_seconds with per-URI, per-status, per-method tags — that is the timer, and it's been exposed since file 01. Adding a second one would double-count and give you two numbers to reconcile. I'll verify it's present in /actuator/prometheus rather than write one.

"Deterministic from a fixed base timestamp" and "moved to Interview within the last three days" can't both be literally true. A hard-coded base means that guarantee is false the day after you pick it. I'm making the base the seeding clock truncated to the day, with every offset fixed relative to it, and exposing SEED_BASE_INSTANT to pin it absolutely. So: identical shape on every run, same people in the same stages with the same relative ages, reproducible byte-for-byte if you pin the env var. Tell me if you'd rather have a literal constant and lose the recency guarantees.

Rate limiting goes in front of authentication, not behind it. Behind, an unauthenticated flood would hammer the auth path unthrottled. In front, it keys on the X-API-Key header directly and falls back to IP.

Rate-limit headers will be on successful responses too, not only 429 — a client can't back off politely if it only learns the limit after breaching it.

No spring-boot-starter-data-redis. Bucket4j's Lettuce module brings lettuce-core transitively; adding Spring Data Redis would pull in an auto-configured health indicator for a client nothing else uses, which can fail the health check for no reason.

My take on search, before your spec

I'd parse it, not pattern-match it. Tokenise, then a small hand-written recursive-descent parser producing a typed AST — stage = SCREENING, timeInStage > 7d, reached(OFFER), not(...), and/or — and a separate compiler from AST to a parameterised query, never string concatenation. Anything that doesn't parse as a predicate falls through to a trigram name match, so "priya sharma" still works in the same box; that's where %> and the 0.5 word-similarity threshold from file 02 earn their keep, with levenshtein as the tiebreak for ranking. Structural conditions map onto the projection columns and reached_mask, which is exactly what those were denormalised for — "reached Offer but not hired" stays one predicate on one row.

Invalid input is the interesting half, and the rule I'd hold to is that a query is never silently narrowed: an unknown field, an unparseable duration, or a dangling and is an error, not a dropped condition, because a wrong answer that looks like a right answer is worse than a refusal. The parser carries the character offset and the token it choked on, so the response says what was expected where, and suggests the nearest known field by edit distance. That comes back as 422 Problem Details in the file-05 shape with the offset and suggestion as extra members, so the UI can underline the bad span rather than showing an empty board. The one case I'd treat as a warning rather than an error is a well-formed query that simply matches nothing — that's an empty result with the parsed interpretation echoed back, so she can see how it was understood.

Stopping here as asked — I'll wait for your spec before building any of it. Now building file 06.

  Ran 1 shell command

Now auth — the part that makes the audit trail real.

  Ran 1 shell command

● Write(backend\src\main\java\com\pipeline\api\SecurityConfig.java)
Wrote 74 lines to backend\src\main\java\com\pipeline\api\SecurityConfig.java
package com.pipeline.api;

import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
… +67 lines

Let me clean up the cruft I just left in that file and create the shared Problems helper both the advice and the filters need.

● Write(backend\src\main\java\com\pipeline\api\Problems.java)
Wrote 60 lines to backend\src\main\java\com\pipeline\api\Problems.java
package com.pipeline.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatusCode;
… +52 lines

● Write(backend\src\main\java\com\pipeline\api\SecurityConfig.java)
Added 20 lines, removed 30 lines
package com.pipeline.api;

import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity

    @Bean
    SecurityFilterChain api(HttpSecurity http, RecruiterProperties recruiter) throws Exception
 {
        return http
                // No browser, no cookies, no sessions: an API key is presented on every
                // No browser, no cookies, no sessions: a key is presented on every
                // request, so there is no session for CSRF to protect.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPol
icy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Health and metrics are how the container and Prometheus find
                        // out whether this is alive; requiring a key would defeat that.
                        // out whether this is alive; a key would defeat that.
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.htm
l")

                        .anyRequest()
                        .denyAll())
                .addFilterBefore(new ApiKeyAuthFilter(recruiter), UsernamePasswordAuthenticati
onFilter.class)
                // Both of these would otherwise emit Spring Security's own error body,
                // which is not the shape the rest of the API promises.
                // Without these, Spring Security emits its own error body, which is not
                // the shape the rest of the API promises.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) -> write(
                                response, request, HttpStatus.UNAUTHORIZED, "unauthenticated",
                                "Present a valid " + ApiKeyAuthFilter.HEADER + " header"))
                        .accessDeniedHandler((request, response, e) -> write(
                                response, request, HttpStatus.FORBIDDEN, "forbidden",
                                "This key does not grant access to that")))
                .httpBasic(Customizer.withDefaults()::customize)
                        .authenticationEntryPoint((request, response, e) -> problems.write(
                                response,
                                problems.of(
                                        HttpStatus.UNAUTHORIZED,
                                        "unauthenticated",
                                        "Unauthorized",
                                        "Present a valid " + ApiKeyAuthFilter.HEADER + " heade
r",
                                        request)))
                        .accessDeniedHandler((request, response, e) -> problems.write(
                                response,
                                problems.of(
                                        HttpStatus.FORBIDDEN,
                                        "forbidden",
                                        "Forbidden",
                                        "This key does not grant access to that",
                                        request))))
                .build();
    }

    private void write(
            jakarta.servlet.http.HttpServletResponse response,
            jakarta.servlet.http.HttpServletRequest request,
            HttpStatus status,
            String slug,
            String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(problems.asJson(problems.of(status, slug, status.getReasonP
hrase(), detail, request)));
    }

    /** Placeholder so the class compiles without an unused-import warning. */
    abstract static class Unused extends OncePerRequestFilter {}
}

Now CurrentActor reads the real principal, and ProblemDetails delegates to the shared helper.
Now rate limiting.

  Ran 2 shell commands

Lettuce is not transitive — it's an optional dependency of that module. My plan comment was wrong; adding it explicitly and correcting the note.

  Ran 3 shell commands

Filesystem search was too slow — I'll let the compiler be the oracle instead.

  Ran 1 shell command

Compiles clean. Now the filter, plus correlation-ID MDC and metrics.

● Write(backend\src\main\java\com\pipeline\api\RateLimitFilter.java)
Wrote 94 lines to backend\src\main\java\com\pipeline\api\RateLimitFilter.java
package com.pipeline.api;

import com.pipeline.infrastructure.RateLimitTier;
import com.pipeline.infrastructure.RateLimiterPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
… +89 lines

  Ran 1 shell command

Now the transitions counter, email masking, and config.

  Ran 1 shell command

Now email masking in logs and the application config.

● Write(backend\src\main\java\com\pipeline\infrastructure\MaskEmails.java)
Wrote 37 lines to backend\src\main\java\com\pipeline\infrastructure\MaskEmails.java
package com.pipeline.infrastructure;

import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks email addresses on the way out of the logger rather than at each call site.
 * Call-site discipline is the thing that eventually slips: one {@code log.info} with a
 * candidate in it and the addresses are in the aggregator forever.
… +28 lines

  Ran 1 shell command

Now the seeder. First moving RecruiterProperties so the seeder can attribute events without breaking the layering rule.

  Ran 2 shell commands

Now the seeder itself.

● Write(backend\src\main\java\com\pipeline\application\SeedProperties.java)
Wrote 11 lines to backend\src\main\java\com\pipeline\application\SeedProperties.java
package com.pipeline.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

public record SeedProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("200") int candidates,
        /** Blank means "today", which keeps the recency guarantees true. Pin to reproduce exac
tly. */
… +2 lines

● Write(backend\src\main\java\com\pipeline\application\SeedPipeline.java)
Wrote 233 lines to backend\src\main\java\com\pipeline\application\SeedPipeline.java
package com.pipeline.application;

import com.pipeline.domain.Actor;
import com.pipeline.domain.Candidate;
import com.pipeline.domain.CandidateCreation;
import com.pipeline.domain.Stage;
import com.pipeline.domain.StageTransitions;
import com.pipeline.domain.TransitionDecision;
import com.pipeline.domain.TransitionRules;
import java.time.Clock;
… +240 lines

  Ran 1 shell command

Now updating the existing tests for auth, and adding the new ones.

  Ran 2 shell commands

Now the new tests for this file.

  Ran 1 shell command

● Write(backend\src\test\java\com\pipeline\api\RateLimitApiTest.java)
Wrote 131 lines to backend\src\test\java\com\pipeline\api\RateLimitApiTest.java
package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
… +124 lines

The rate-limit key is the API key, so tests in one class would share a bucket and depend on ordering. Restructuring: one comprehensive HTTP test, and per-key behaviour tested at the port.

● Write(backend\src\test\java\com\pipeline\api\RateLimitApiTest.java)
Added 22 lines, removed 64 lines
import org.springframework.test.web.servlet.MvcResult;

/**
 * Small limits rather than the production ones, so the burst is a handful of requests
 * instead of hundreds. The behaviour under test is the filter, not the number.
 * Small limits rather than the production ones, so a burst is a handful of requests
 * rather than hundreds. What is under test is the filter, not the number.
 *
 * <p>Written as one long test on purpose. There is a single valid API key, so every
 * request in this class draws on the same bucket; split across methods these
 * assertions would depend on execution order. Per-key isolation is covered at the port
 * instead, in {@code RateLimiterTest}, where keys can actually differ.
 */
@TestPropertySource(
        properties = {

class RateLimitApiTest extends ApiTest {

    @Test
    void aBurstPastTheWriteLimitIsRefusedWithTheRightHeaders() throws Exception {
        String key = "burst-" + UUID.randomUUID();

    void aBurstPastTheWriteLimitIsRefusedAndTheRestOfTheApiStillWorks() throws Exception {
        for (int i = 1; i <= 3; i++) {
            MvcResult allowed = createWith(key);
            MvcResult allowed = create();
            assertThat(allowed.getResponse().getStatus()).as("request %d", i).isEqualTo(201);
            assertThat(allowed.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("3");
            // Headers on success too, so a client can slow down before it is stopped.
            // Headers on success too: a client that only learns the limit by breaching
            // it has no way to avoid breaching it.
            assertThat(allowed.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo(Str
ing.valueOf(3 - i));
        }

        MvcResult refused = createWith(key);
        MvcResult refused = create();

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(refused.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(Long.parseLong(refused.getResponse().getHeader("X-RateLimit-Reset"))).isNot
Negative();
        assertThat(Long.parseLong(refused.getResponse().getHeader("Retry-After"))).isPositive(
);
    }

    @Test
    void theRefusalIsTheSameProblemShapeAsEveryOtherError() throws Exception {
        String key = "shape-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            createWith(key);
        }

        MvcResult refused = createWith(key);
        // Same envelope as every other error in the API.
        JsonNode body = json.readTree(refused.getResponse().getContentAsString());

        assertThat(refused.getResponse().getContentType()).startsWith("application/problem+jso
n");
        for (String field : Problems.requiredFields()) {
            assertThat(body.has(field)).as("field %s", field).isTrue();

        assertThat(body.get("type").asText()).isEqualTo("https://pipeline.example/problems/rat
e-limited");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("correlationId").asText()).isNotBlank();
    }

    /** Separate buckets: exhausting writes must not stop her looking at the board. */
    @Test
    void readsAndWritesAreTieredIndependently() throws Exception {
        String key = "tiers-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            createWith(key);
        }

        MvcResult read = mvc.perform(get("/api/v1/pipeline").header("X-API-Key", key)).andRetu
rn();

        // Separate buckets: exhausting writes must not stop her looking at the board.
        MvcResult read = mvc.perform(get("/api/v1/pipeline")).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(read.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("5");
    }

    /** Keyed per caller, so one noisy client cannot exhaust everyone else's budget. */
    @Test
    void oneCallerExhaustingTheirBucketDoesNotAffectAnother() throws Exception {
        String noisy = "noisy-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            createWith(noisy);
        }
        assertThat(createWith(noisy).getResponse().getStatus()).isEqualTo(429);

        assertThat(createWith("quiet-" + UUID.randomUUID()).getResponse().getStatus())
                .isEqualTo(201);
        String metrics = mvc.perform(get("/actuator/prometheus"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(metrics).contains("pipeline_rate_limit_rejections_total");
        assertThat(metrics).contains("tier=\"write\"");
    }

    @Test

        assertThat(health.getResponse().getHeader("X-RateLimit-Limit")).isNull();
    }

    @Test
    void rejectionsAreCounted() throws Exception {
        String key = "counted-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            createWith(key);
        }

        String metrics = mvc.perform(get("/actuator/prometheus"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(metrics).contains("pipeline_rate_limit_rejections_total");
        assertThat(metrics).contains("tier=\"write\"");
    }

    private MvcResult createWith(String apiKey) throws Exception {
    private MvcResult create() throws Exception {
        return mvc.perform(post("/api/v1/candidates")
                        .header("X-API-Key", API_KEY)
                        .header("X-Caller", apiKey)
                        .with(request -> {
                            request.addHeader("X-RateLimit-Key", apiKey);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Burst Test", "email": "%s@example.com"}

 Write(backend\src\test\java\com\pipeline\ratelimit\RateLimiterTest.java)
Wrote 98 lines to backend\src\test\java\com\pipeline\ratelimit\RateLimiterTest.java
package com.pipeline.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.infrastructure.RateLimitConfig;
import com.pipeline.infrastructure.RateLimitProperties;
import com.pipeline.infrastructure.RateLimitTier;
import com.pipeline.infrastructure.RateLimiterPort;
import io.lettuce.core.RedisClient;
import java.time.Duration;
… +90 lines

Now the seeder and observability tests.

● Write(backend\src\test\java\com\pipeline\persistence\SeedPipelineTest.java)
Wrote 152 lines to backend\src\test\java\com\pipeline\persistence\SeedPipelineTest.java
package com.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipeline.application.SeedPipeline;
import com.pipeline.db.SchemaFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
… +143 lines

● Write(backend\src\test\java\com\pipeline\api\ObservabilityTest.java)
Wrote 140 lines to backend\src\test\java\com\pipeline\api\ObservabilityTest.java
package com.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.pipeline.infrastructure.MaskEmails;
import java.io.ByteArrayOutputStream;
… +135 lines

  Ran 3 shell commands

Three separate problems: seeding disabled by a property source that outranks the subclass, the rate limiter bucket bleeding across test classes, and my guess at where ECS puts MDC.

  Ran 2 shell commands

ECS promotes MDC to root fields, not under /mdc. Fixing the test.

  Ran 2 shell commands

Two real problems. Let me measure the actuator one rather than guess.

  Ran 2 shell commands

Found both. Boot disables metrics export in tests unless @AutoConfigureObservability; and the seeder was skipping because other test classes had already put candidates in the shared job. Fixing both.

  Ran 1 shell command

Now pointing the seed test at its own database.

  Ran 2 shell commands

Two problems in one: the base class's datasource registration won, and the mutable test clock (fixed at 2025) disagrees with Postgres now(). Making the seed test fully standalone fixes both.

● Write(backend\src\test\java\com\pipeline\persistence\SeedPipelineTest.java)
Added 40 lines, removed 48 lines
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The guarantees the demo depends on, asserted as SQL rather than as Java, because what
 * matters is that a query the search feature will run actually returns rows.
 *
 * <p>Runs against its own database on the shared container. It has to: the seeder is
 * idempotent by checking whether the job already has candidates, and every other API
 * test class puts candidates in that same job, so on the shared database this would
 * find one already populated and correctly decline to do anything.
 * <p>Stands alone rather than extending the shared base, for two reasons that both bite.
 * It needs its own database: the seeder is idempotent by checking whether the job
 * already has candidates, and every other test class puts candidates in the shared one,
 * so it would correctly decline to do anything. And it needs the real clock: the shared
 * base pins time to 2025, while these assertions compare against Postgres now(), so a
 * frozen clock would make "moved to Interview in the last three days" permanently false.
 */
@TestPropertySource(properties = {"pipeline.seed.enabled=true", "pipeline.seed.candidates=200"
})
class SeedPipelineTest extends PersistenceTest {
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "pipeline.seed.enabled=true",
            "pipeline.seed.candidates=200",
            "pipeline.auth.api-key=seed-test-key"
        })
class SeedPipelineTest {

    private static final String SEED_DB = SchemaFixture.freshDatabase("seed_test");

    @Autowired SeedPipeline seeder;

    /** Registered after the base class's, so this url is the one that survives. */
    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SEED_DB);
        registry.add("spring.datasource.username", () -> "pipeline_app");
        registry.add("spring.datasource.password", () -> SchemaFixture.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
    }

    @Test
    void thereAreTwoHundredCandidates() throws SQLException {
        assertThat(count("SELECT count(*) FROM candidate WHERE job_id = '" + CANONICAL_JOB + "
'"))
                .isEqualTo(200);
        assertThat(count("SELECT count(*) FROM candidate")).isEqualTo(200);
    }

    /** The headline search query from the brief. */

        assertThat(count(
                        """
                        SELECT count(*) FROM candidate
                         WHERE job_id = '%s' AND current_stage = 'SCREENING' AND NOT is_termin
al
                         WHERE current_stage = 'SCREENING' AND NOT is_terminal
                           AND current_stage_since < now() - interval '7 days'
                        """
                                .formatted(CANONICAL_JOB)))
                        """))
                .isGreaterThanOrEqualTo(8);
    }


    void fiveMovedToInterviewWithinTheLastThreeDays() throws SQLException {
        assertThat(count(
                        """
                        SELECT count(*) FROM stage_event e
                          JOIN candidate c ON c.id = e.candidate_id
                         WHERE c.job_id = '%s' AND e.to_stage = 'INTERVIEW'
                           AND e.occurred_at >= now() - interval '3 days'
                        """
                                .formatted(CANONICAL_JOB)))
                        SELECT count(*) FROM stage_event
                         WHERE to_stage = 'INTERVIEW' AND occurred_at >= now() - interval '3 d
ays'
                        """))
                .isGreaterThanOrEqualTo(5);
    }


        assertThat(count(
                        """
                        SELECT count(*) FROM candidate
                         WHERE job_id = '%s'
                           AND (reached_mask & 8) = 8 AND (reached_mask & 16) = 0
                         WHERE (reached_mask & 8) = 8 AND (reached_mask & 16) = 0
                           AND current_stage = 'REJECTED'
                        """
                                .formatted(CANONICAL_JOB)))
                        """))
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void threeWereHired() throws SQLException {
        assertThat(count("SELECT count(*) FROM candidate WHERE job_id = '" + CANONICAL_JOB
                        + "' AND current_stage = 'HIRED'"))
        assertThat(count("SELECT count(*) FROM candidate WHERE current_stage = 'HIRED'"))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void priyaSharmaExists() throws SQLException {
        assertThat(count("SELECT count(*) FROM candidate WHERE job_id = '" + CANONICAL_JOB
                        + "' AND full_name = 'Priya Sharma'"))
        assertThat(count("SELECT count(*) FROM candidate WHERE full_name = 'Priya Sharma'"))
                .isEqualTo(1);
    }

    /** Histories, not a pile of rows all written at once. */
    @Test
    void eventsAreGenuinelyBackdatedAndSpreadOut() throws SQLException {
        assertThat(count(
                        """
                        SELECT count(*) FROM stage_event e
                          JOIN candidate c ON c.id = e.candidate_id
                         WHERE c.job_id = '%s' AND e.occurred_at < now() - interval '30 days'
                        """
                                .formatted(CANONICAL_JOB)))
        assertThat(count("SELECT count(*) FROM stage_event WHERE occurred_at < now() - interv
al '30 days'"))
                .isGreaterThan(20);

        assertThat(count(
                        """
                        SELECT count(DISTINCT date_trunc('day', occurred_at)) FROM stage_even
t e
                          JOIN candidate c ON c.id = e.candidate_id
                         WHERE c.job_id = '%s'
                        """
                                .formatted(CANONICAL_JOB)))
        assertThat(count("SELECT count(DISTINCT date_trunc('day', occurred_at)) FROM stage_ev
ent"))
                .isGreaterThan(20);
    }

    @Test
    void everyStageOfTheBoardHasSomebodyOnIt() throws SQLException {
        assertThat(count("SELECT count(DISTINCT current_stage) FROM candidate WHERE job_id =
'"
                        + CANONICAL_JOB + "'"))
                .isEqualTo(6);
        assertThat(count("SELECT count(DISTINCT current_stage) FROM candidate")).isEqualTo(6)
;
    }

    /** Every seeded event is attributed, like every other event. */
    @Test
    void seededEventsCarryAnActor() throws SQLException {
        assertThat(count("SELECT count(*) FROM stage_event WHERE actor_id IS NULL OR actor_na
me IS NULL"))
                .isZero();
    }

    /** Restarting the container must not double the board. */
    @Test
    void runningAgainAddsNothing() throws SQLException {
        int before = count("SELECT count(*) FROM candidate WHERE job_id = '" + CANONICAL_JOB
+ "'");
        int candidatesBefore = count("SELECT count(*) FROM candidate");
        int eventsBefore = count("SELECT count(*) FROM stage_event");

        seeder.run(null);

        assertThat(count("SELECT count(*) FROM candidate WHERE job_id = '" + CANONICAL_JOB +
"'"))
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM candidate")).isEqualTo(candidatesBefore);
        assertThat(count("SELECT count(*) FROM stage_event")).isEqualTo(eventsBefore);
    }


  Ran 1 shell command

One failure left, and it's a real observation: nothing in the app logs during a request, so my log.info ran after the filter had already cleared the MDC. Testing the mechanism from inside a request instead.

  Ran 1 shell command

All 145 green. Now config and the compose end-to-end check.

  Ran 3 shell commands

All guarantees met against a real docker compose up. Now the live rate limiter on the Redis backend, auth, and restart idempotency.

  Ran 4 shell commands

Done. 145 tests green, and everything below was executed live against docker compose up on an empty volume.

Acceptance checks
- Boot disables metrics export in tests. /actuator/prometheus was returning 404, so any metrics assertion would have been asserting on nothing. Needs @AutoConfigureObservability.
- @DynamicPropertySource outranks a subclass's @TestPropertySource, so the seed test could never turn seeding back on. The test defaults moved to @TestPropertySource on the base.
- The seed test can't share a database. Rows can't be cleaned between classes (the log refuses DELETE), so the seeder correctly found the shared job already populated and declined. It now takes its own database on the same container — and its own real clock, since the shared base pins time to 2025 while these assertions compare against Postgres now().

One honest gap: nothing in the application logs mid-request, so the live "emails masked in logs" check had nothing to mask. Masking is verified by unit tests over the masker and by capturing real ECS output in ObservabilityTest; the production value is for lines added later. I deliberately did not add an access log just to make that observable.

My take on search is in the message above, before I started — waiting for your spec now.
-->

