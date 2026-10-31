package com.paisatrack.platform.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

import com.paisatrack.platform.exception.FieldValidationException;

/** Amount rules shared by every money field. Precision follows the currency (INR 2 decimals, JPY 0). */
public final class MoneyRules {

    /** Fits NUMERIC(19,4) with room to spare. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    private MoneyRules() {
    }

    public static int fractionDigits(String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return digits < 0 ? 2 : digits;
    }

    /** Positive, at most {@link #MAX_AMOUNT}, and no more decimals than the currency allows. */
    public static void requireValidAmount(String field, BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw new FieldValidationException(field, "must be greater than 0");
        }
        requireValidMagnitude(field, amount, currency);
    }

    /** Like {@link #requireValidAmount} but allows zero and negatives (e.g. opening balances). */
    public static void requireValidMagnitude(String field, BigDecimal amount, String currency) {
        if (amount.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new FieldValidationException(field, "is too large");
        }
        int digits = fractionDigits(currency);
        if (amount.stripTrailingZeros().scale() > digits) {
            throw new FieldValidationException(field,
                    digits == 0 ? currency + " amounts can't have decimals" : "can have at most " + digits + " decimals for " + currency);
        }
    }

    /** Stored value at the currency's scale, e.g. 150.0000 INR becomes 150.00. */
    public static BigDecimal scaled(BigDecimal amount, String currency) {
        return amount == null ? null : amount.setScale(fractionDigits(currency), RoundingMode.HALF_EVEN);
    }
}
