package com.paisatrack.platform.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * New-password policy: 8-64 chars, at most 72 UTF-8 bytes (BCrypt limit), a letter and a digit,
 * no leading/trailing whitespace. Null passes.
 */
@Documented
@Constraint(validatedBy = Password.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Password {

    int MIN_LENGTH = 8;
    int MAX_LENGTH = 64;
    int MAX_BYTES = 72;

    String message() default "must be 8-64 characters and contain at least one letter and one digit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<Password, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            int length = value.codePointCount(0, value.length());
            if (length < MIN_LENGTH || length > MAX_LENGTH
                    || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                    || !value.strip().equals(value)) {
                return false;
            }
            return value.codePoints().anyMatch(Character::isLetter)
                    && value.codePoints().anyMatch(Character::isDigit);
        }
    }
}
