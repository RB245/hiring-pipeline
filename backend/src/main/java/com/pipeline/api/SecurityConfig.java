package com.pipeline.api;

import com.pipeline.application.RecruiterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(RecruiterProperties.class)
class SecurityConfig {

    private final Problems problems;

    SecurityConfig(Problems problems) {
        this.problems = problems;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, RecruiterProperties recruiter) throws Exception {
        return http
                // No browser, no cookies, no sessions: a key is presented on every
                // request, so there is no session for CSRF to protect.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Health and metrics are how the container and Prometheus find
                        // out whether this is alive; a key would defeat that.
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .hasRole("RECRUITER")
                        .anyRequest()
                        .denyAll())
                .addFilterBefore(new ApiKeyAuthFilter(recruiter), UsernamePasswordAuthenticationFilter.class)
                // Without these, Spring Security emits its own error body, which is not
                // the shape the rest of the API promises.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) -> problems.write(
                                response,
                                problems.of(
                                        HttpStatus.UNAUTHORIZED,
                                        "unauthenticated",
                                        "Unauthorized",
                                        "Present a valid " + ApiKeyAuthFilter.HEADER + " header",
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
}
