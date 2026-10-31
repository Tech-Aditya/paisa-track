package com.paisatrack.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ValidationConstraintsTest {

    private final Password.Validator password = new Password.Validator();
    private final CurrencyCode.Validator currency = new CurrencyCode.Validator();
    private final TimeZoneId.Validator timezone = new TimeZoneId.Validator();

    @ParameterizedTest
    @ValueSource(strings = {"Secret123", "पासवर्ड1234", "a1a1a1a1", "Long password with spaces 9"})
    void acceptsReasonablePasswords(String value) {
        assertThat(password.isValid(value, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Short1", "lettersonly", "12345678", " Leading1", "Trailing1 ",
            "Abcdefghij1234567890Abcdefghij1234567890Abcdefghij1234567890Abcde"})
    void rejectsWeakOrOversizedPasswords(String value) {
        assertThat(password.isValid(value, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INR", "USD", "EUR", "GBP", "AED", "SGD", "JPY", "XOF"})
    void acceptsIsoCurrencies(String code) {
        assertThat(currency.isValid(code, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"inr", "RUPEE", "ABC", "XXX", "XAU", "XTS", ""})
    void rejectsUnknownOrNonCurrencyCodes(String code) {
        assertThat(currency.isValid(code, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Kolkata", "Europe/London", "America/New_York", "UTC"})
    void acceptsIanaZones(String zone) {
        assertThat(timezone.isValid(zone, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+05:30", "IST-ish", "Mars/Base", ""})
    void rejectsOffsetsAndUnknownZones(String zone) {
        assertThat(timezone.isValid(zone, null)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    void nullIsLeftToNotNull(String value) {
        assertThat(password.isValid(value, null)).isTrue();
        assertThat(currency.isValid(value, null)).isTrue();
        assertThat(timezone.isValid(value, null)).isTrue();
    }
}
