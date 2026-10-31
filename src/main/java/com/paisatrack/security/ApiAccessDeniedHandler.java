package com.paisatrack.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.paisatrack.exception.ErrorCode;

@Component
class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorWriter writer;

    ApiAccessDeniedHandler(SecurityErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        writer.write(response, ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action");
    }
}
