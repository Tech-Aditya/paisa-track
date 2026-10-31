package com.paisatrack.platform.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Error body: a stable {@code ErrorCode} name plus per-field problems for validation errors. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, List<FieldViolation> fields) {

    public static ApiError of(String code) {
        return new ApiError(code, List.of());
    }

    public record FieldViolation(String field, String message) {
    }
}
