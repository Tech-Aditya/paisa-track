package com.paisatrack.platform.security.ratelimit;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.security.SecurityErrorWriter;

/**
 * Throttles POST /api/v1/auth/** per client IP and endpoint against credential stuffing.
 * Runs before Spring Security, so rejected calls cost no hashing or DB work.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);
    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";

    private final RateLimitProperties properties;
    private final FixedWindowRateLimiter limiter;
    private final SecurityErrorWriter errorWriter;

    AuthRateLimitFilter(RateLimitProperties properties, Clock clock, SecurityErrorWriter errorWriter) {
        this.properties = properties;
        this.limiter = new FixedWindowRateLimiter(properties.auth().maxRequests(), properties.auth().window(), clock);
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled()
                || !"POST".equals(request.getMethod())
                || !request.getRequestURI().startsWith(AUTH_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr() + '|' + request.getRequestURI();
        FixedWindowRateLimiter.Decision decision = limiter.tryAcquire(key);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, (long) Math.ceil(decision.retryAfter().toMillis() / 1000.0));
        log.warn("Rate limit hit path={} retryAfter={}s", request.getRequestURI(), retryAfterSeconds);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        errorWriter.write(response, ErrorCode.RATE_LIMITED,
                "Too many requests. Try again in " + retryAfterSeconds + " seconds.");
    }

    @Scheduled(fixedDelayString = "PT1M")
    void evictExpiredWindows() {
        limiter.evictExpired();
    }
}
