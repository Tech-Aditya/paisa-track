package com.paisatrack.platform.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.paisatrack.platform.logging.RequestCorrelation;

/**
 * Puts the authenticated user's id in the MDC, so production JSON logs carry a userId field.
 * The dev console pattern leaves it out to keep lines short. Registered inside the security chain.
 */
class UserMdcFilter extends OncePerRequestFilter {

    static final String MDC_KEY = RequestCorrelation.USER_MDC_KEY;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            MDC.put(MDC_KEY, jwt.getToken().getSubject());
        }
        // Left in place so the access-log line carries it; CorrelationIdFilter (outermost) clears it.
        chain.doFilter(request, response);
    }
}
