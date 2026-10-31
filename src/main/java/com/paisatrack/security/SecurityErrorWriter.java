package com.paisatrack.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.paisatrack.common.api.ApiError;
import com.paisatrack.common.api.ApiResponse;
import com.paisatrack.exception.ErrorCode;

/** Writes the error envelope from filters, which run outside the MVC exception handler. */
@Component
public class SecurityErrorWriter {

    private final JsonMapper jsonMapper;

    SecurityErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), ApiResponse.failure(message, ApiError.of(code.name())));
    }
}
