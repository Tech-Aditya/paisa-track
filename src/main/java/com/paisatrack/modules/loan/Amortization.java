package com.paisatrack.modules.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.paisatrack.platform.exception.FieldValidationException;

/**
 * Reducing-balance EMI maths, as Indian banks use it. Monthly rate r = annual% / 1200;
 * EMI = P·r·(1+r)^n / ((1+r)^n − 1). Each month's interest is on the opening balance, rounded to
 * the currency's scale; the rest of the EMI repays principal. The last row clears any rounding residue.
 */
final class Amortization {

    private static final MathContext MC = MathContext.DECIMAL128;
    static final int MAX_ROWS = 600;

    private Amortization() {
    }

    record Row(int number, LocalDate dueDate, BigDecimal opening, BigDecimal emi, BigDecimal principal,
               BigDecimal interest, BigDecimal closing) {
    }

    static BigDecimal monthlyRate(BigDecimal annualRatePercent) {
        return annualRatePercent.divide(BigDecimal.valueOf(1200), MC);
    }

    /** Standard EMI, rounded half-up to the currency's scale. Zero interest splits the principal evenly. */
    static BigDecimal emi(BigDecimal principal, BigDecimal annualRatePercent, int months, int scale) {
        if (annualRatePercent.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(months), scale, RoundingMode.UP);
        }
        BigDecimal r = monthlyRate(annualRatePercent);
        BigDecimal growth = BigDecimal.ONE.add(r).pow(months, MC);
        return principal.multiply(r, MC).multiply(growth, MC)
                .divide(growth.subtract(BigDecimal.ONE), MC)
                .setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Installments starting at {@code firstNumber} from {@code opening}, until the balance is zero.
     * With {@code lastNumber} set, that row repays whatever is left (used for the original schedule).
     * Due dates are the first EMI date plus (number − 1) months, so month-end dates don't drift.
     */
    static List<Row> schedule(BigDecimal opening, BigDecimal annualRatePercent, BigDecimal emi, LocalDate firstEmiDate,
                              int firstNumber, Integer lastNumber, int scale) {
        BigDecimal r = monthlyRate(annualRatePercent);
        List<Row> rows = new ArrayList<>();
        BigDecimal balance = opening;
        int number = firstNumber;
        while (balance.signum() > 0) {
            BigDecimal interest = balance.multiply(r, MC).setScale(scale, RoundingMode.HALF_UP);
            BigDecimal principal = emi.subtract(interest);
            if (principal.signum() <= 0) {
                throw new FieldValidationException("emiAmount",
                        "is too small to cover the interest (" + interest + " a month), so the loan would never be repaid");
            }
            boolean last = (lastNumber != null && number >= lastNumber) || number - firstNumber + 1 >= MAX_ROWS;
            if (principal.compareTo(balance) >= 0 || last) {
                principal = balance;
            }
            BigDecimal closing = balance.subtract(principal);
            rows.add(new Row(number, firstEmiDate.plusMonths(number - 1L), balance, principal.add(interest),
                    principal, interest, closing));
            balance = closing;
            number++;
        }
        return rows;
    }
}
