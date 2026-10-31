package com.paisatrack.modules.user;

/** Input for creating a user. Null currency/timezone fall back to INR and Asia/Kolkata. */
public record NewUser(String email, String rawPassword, String fullName, String defaultCurrency, String timezone) {
}
