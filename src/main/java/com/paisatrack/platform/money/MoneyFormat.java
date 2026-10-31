package com.paisatrack.platform.money;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/** Amounts in sentences (reminders, insights): "₹1,25,000.00", Indian digit grouping. */
public final class MoneyFormat {

    private static final Locale INDIA = Locale.forLanguageTag("en-IN");

    private MoneyFormat() {
    }

    public static String format(BigDecimal amount, String currency) {
        NumberFormat format = NumberFormat.getCurrencyInstance(INDIA);
        try {
            Currency c = Currency.getInstance(currency);
            format.setCurrency(c);
            format.setMinimumFractionDigits(c.getDefaultFractionDigits());
            format.setMaximumFractionDigits(c.getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            return amount.toPlainString() + " " + currency;
        }
        return format.format(amount);
    }
}
