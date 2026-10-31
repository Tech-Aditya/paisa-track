package com.paisatrack.exception;

import org.springframework.http.HttpStatus;

/** Client-facing error codes. Part of the API contract: add freely, never rename. */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),
    DUPLICATE_NAME(HttpStatus.CONFLICT),
    CATEGORY_IN_USE(HttpStatus.CONFLICT),
    DUPLICATE_BUDGET(HttpStatus.CONFLICT),
    DATA_CONFLICT(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    CURRENT_PASSWORD_INCORRECT(HttpStatus.UNPROCESSABLE_CONTENT),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
    TRANSACTION_VOIDED(HttpStatus.UNPROCESSABLE_CONTENT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
