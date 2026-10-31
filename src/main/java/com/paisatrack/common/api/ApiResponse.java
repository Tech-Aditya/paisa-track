package com.paisatrack.common.api;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paisatrack.logging.RequestCorrelation;

/** Envelope for every API response. Exactly one of {@code data} or {@code error} is set. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        ApiError error,
        String requestId,
        Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return ok("OK", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, null, RequestCorrelation.currentRequestId(), Instant.now());
    }

    public static ApiResponse<Void> failure(String message, ApiError error) {
        return new ApiResponse<>(false, message, null, error, RequestCorrelation.currentRequestId(), Instant.now());
    }
}
