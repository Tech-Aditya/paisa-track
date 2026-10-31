package com.paisatrack.validation;

import java.util.Currency;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Accepted ISO 4217 codes: the JDK's list minus non-currencies (metals, SDR, test codes).
 * Moves to a database table in the multi-currency phase.
 */
public final class SupportedCurrencies {

    public static final String DEFAULT = "INR";

    private static final Pattern ISO_CODE = Pattern.compile("[A-Z]{3}");

    private static final Set<String> NON_CURRENCY_CODES = Set.of(
            "XAG", "XAU", "XPD", "XPT",            // precious metals
            "XBA", "XBB", "XBC", "XBD",            // bond market units
            "XDR", "XSU", "XUA",                   // supranational accounting units
            "XTS", "XXX");                         // testing / no currency

    private static final Set<String> CODES = Currency.getAvailableCurrencies().stream()
            .map(Currency::getCurrencyCode)
            .filter(code -> !NON_CURRENCY_CODES.contains(code))
            .collect(Collectors.toUnmodifiableSet());

    private SupportedCurrencies() {
    }

    public static boolean isSupported(String code) {
        return code != null && ISO_CODE.matcher(code).matches() && CODES.contains(code);
    }
}
