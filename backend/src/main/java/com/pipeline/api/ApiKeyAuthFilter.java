package com.pipeline.api;

import com.pipeline.application.RecruiterProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Deliberately not a login flow. One static key resolves to one recruiter, which is
 * enough to give every event a real actor and no more than a take-home warrants.
 */
class ApiKeyAuthFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";

    private final RecruiterProperties properties;

    ApiKeyAuthFilter(RecruiterProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && constantTimeEquals(presented, properties.apiKey())) {
            Recruiter recruiter = new Recruiter(properties.recruiterId(), properties.recruiterName());
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            recruiter, null, List.of(new SimpleGrantedAuthority("ROLE_RECRUITER"))));
        }
        chain.doFilter(request, response);
    }

    /** Length-independent comparison, so a wrong key cannot be found one character at a time. */
    private static boolean constantTimeEquals(String presented, String expected) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
