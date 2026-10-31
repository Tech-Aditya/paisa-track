package com.paisatrack.platform.logging;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags each request with an X-Request-Id and writes one access-log line:
 * {@code GET /api/v1/users/me 200 12ms}. Server errors and slow requests log at WARN.
 * Query strings, headers and bodies are never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("access");

    private final long slowMillis;

    CorrelationIdFilter(LoggingProperties properties) {
        this.slowMillis = properties.slowRequestThreshold().toMillis();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(RequestCorrelation.HEADER);
        String requestId = RequestCorrelation.isAcceptable(supplied) ? supplied : RequestCorrelation.newRequestId();

        MDC.put(RequestCorrelation.MDC_KEY, requestId);
        response.setHeader(RequestCorrelation.HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            logAccess(request, response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove(RequestCorrelation.MDC_KEY);
        }
    }

    private void logAccess(HttpServletRequest request, int status, long millis) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        if (status >= 500) {
            log.warn("{} {} {} {}ms", method, uri, status, millis);
        } else if (millis >= slowMillis) {
            log.warn("{} {} {} {}ms slow", method, uri, status, millis);
        } else if (isNoise(uri)) {
            log.debug("{} {} {} {}ms", method, uri, status, millis);
        } else {
            log.info("{} {} {} {}ms", method, uri, status, millis);
        }
    }

    // Health probes and static assets would drown out real traffic.
    private static boolean isNoise(String uri) {
        return uri.startsWith("/actuator") || uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/favicon") || (uri.startsWith("/app/") && uri.indexOf('.') > 0);
    }
}
