package com.paisatrack.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.platform.validation.CurrencyCode;
import com.paisatrack.platform.validation.Password;
import com.paisatrack.platform.validation.TimeZoneId;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Password String password,
        @NotBlank @Size(max = 100) String fullName,
        @Schema(description = "ISO 4217 code; defaults to INR", example = "INR") @CurrencyCode String defaultCurrency,
        @Schema(description = "IANA time zone; defaults to Asia/Kolkata", example = "Asia/Kolkata") @TimeZoneId String timezone) {

    @Override
    public String toString() {
        return "RegisterRequest[email=***, password=***]";
    }
}
