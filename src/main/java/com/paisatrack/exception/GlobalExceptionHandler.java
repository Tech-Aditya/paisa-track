package com.paisatrack.exception;

import java.util.List;
import java.util.stream.Stream;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.paisatrack.common.api.ApiError;
import com.paisatrack.common.api.ApiError.FieldViolation;
import com.paisatrack.common.api.ApiResponse;

/** Maps every exception to the {@link ApiResponse} envelope. Details are logged, never returned. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FieldValidationException.class)
    ResponseEntity<ApiResponse<Void>> handleFieldValidation(FieldValidationException ex) {
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                List.of(new FieldViolation(ex.getField(), ex.getMessage())));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex) {
        return respond(ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> fields = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(lastPathNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                List.of(new FieldViolation(ex.getName(), "has an invalid value")));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                "The record was changed by another request. Reload it and try again.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation constraint={}", constraintName(ex));
        return respond(ErrorCode.DATA_CONFLICT, "The request conflicts with existing data");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        return respond(ErrorCode.UNAUTHENTICATED, "Authentication is required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return respond(ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
        List<FieldViolation> globals = ex.getBindingResult().getGlobalErrors().stream()
                .map(e -> new FieldViolation(e.getObjectName(), e.getDefaultMessage()))
                .toList();
        return body(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                concat(fields, globals), headers);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> fields = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return body(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields, headers);
    }

    /** Remaining Spring MVC errors: unreadable body, bad method/media type, unknown path, etc. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = switch (statusCode.value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 400 -> ErrorCode.MALFORMED_REQUEST;
            default -> statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.MALFORMED_REQUEST;
        };
        if (statusCode.is5xxServerError()) {
            log.error("Request failed", ex);
        }
        String message = switch (code) {
            case RESOURCE_NOT_FOUND -> "The requested resource was not found";
            case METHOD_NOT_ALLOWED -> "HTTP method not supported for this resource";
            case UNSUPPORTED_MEDIA_TYPE -> "Unsupported content type";
            case INTERNAL_ERROR -> "An unexpected error occurred";
            default -> "The request could not be processed. Check its format and parameters.";
        };
        ApiResponse<Void> envelope = ApiResponse.failure(message, ApiError.of(code.name()));
        return ResponseEntity.status(statusCode).headers(headers).body(envelope);
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message) {
        return respond(code, message, List.of());
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message, List<FieldViolation> fields) {
        HttpStatus status = code.status();
        return ResponseEntity.status(status).body(ApiResponse.failure(message, new ApiError(code.name(), fields)));
    }

    private static ResponseEntity<Object> body(ErrorCode code, String message, List<FieldViolation> fields, HttpHeaders headers) {
        return ResponseEntity.status(code.status()).headers(headers)
                .body(ApiResponse.failure(message, new ApiError(code.name(), fields)));
    }

    // Binding failures (e.g. "abc" for a UUID) carry Java type names; replace them with a plain message.
    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.isBindingFailure() ? "has an invalid value" : error.getDefaultMessage());
    }

    private static List<FieldViolation> concat(List<FieldViolation> a, List<FieldViolation> b) {
        return b.isEmpty() ? a : Stream.concat(a.stream(), b.stream()).toList();
    }

    // Only the constraint name: driver messages can contain user data (e.g. the duplicate email).
    private static String constraintName(DataIntegrityViolationException ex) {
        return ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve
                ? cve.getConstraintName()
                : "unknown";
    }

    private static String lastPathNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
