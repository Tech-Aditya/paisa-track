package com.paisatrack.exception;

/** A validation failure on one request field that only the service layer can detect. */
public class FieldValidationException extends ApiException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
