package com.pipeline.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a caller's correlation id or mints one, puts it in the MDC so every log line
 * for the request carries it, echoes it on the response, and parks it where the error
 * handlers can find it. One id ties a support conversation to a response body to the
 * lines in the log.
 *
 * <p>First in the chain: a request rejected by the rate limiter or by authentication
 * still needs to be traceable, and both of those run before any controller.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationId extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    static final String ATTRIBUTE = CorrelationId.class.getName();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;

        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled. Leaving this set would stamp the next request with
            // the previous one's id, which is worse than having no id at all.
            MDC.remove(MDC_KEY);
        }
    }

    static String current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
