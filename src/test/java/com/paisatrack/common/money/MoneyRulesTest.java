package com.paisatrack.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.paisatrack.exception.FieldValidationException;

class MoneyRulesTest {

    @Test
    void precisionFollowsTheCurrency() {
        assertThat(MoneyRules.fractionDigits("INR")).isEqualTo(2);
        assertThat(MoneyRules.fractionDigits("JPY")).isZero();
        assertThat(MoneyRules.fractionDigits("KWD")).isEqualTo(3);
    }

    @Test
    void trailingZerosDontCountAsExtraDecimals() {
        assertThatCode(() -> MoneyRules.requireValidAmount("amount", new BigDecimal("10.5000"), "INR")).doesNotThrowAnyException();
        assertThatCode(() -> MoneyRules.requireValidAmount("amount", new BigDecimal("100.00"), "JPY")).doesNotThrowAnyException();
        assertThatCode(() -> MoneyRules.requireValidAmount("amount", new BigDecimal("1.125"), "KWD")).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveTooPreciseAndHugeAmounts() {
        for (String bad : new String[] {"0", "-1", "0.001"}) {
            assertThatThrownBy(() -> MoneyRules.requireValidAmount("amount", new BigDecimal(bad), "INR"))
                    .isInstanceOf(FieldValidationException.class);
        }
        assertThatThrownBy(() -> MoneyRules.requireValidAmount("amount", new BigDecimal("1.5"), "JPY"))
                .hasMessageContaining("can't have decimals");
        assertThatThrownBy(() -> MoneyRules.requireValidAmount("amount", new BigDecimal("1000000000001"), "INR"))
                .hasMessageContaining("too large");
    }

    @Test
    void magnitudeCheckAllowsZeroAndNegatives() {
        assertThatCode(() -> MoneyRules.requireValidMagnitude("openingBalance", new BigDecimal("-250.75"), "INR"))
                .doesNotThrowAnyException();
        assertThatCode(() -> MoneyRules.requireValidMagnitude("openingBalance", BigDecimal.ZERO, "INR"))
                .doesNotThrowAnyException();
    }

    @Test
    void scaledUsesCurrencyScale() {
        assertThat(MoneyRules.scaled(new BigDecimal("150.0000"), "INR")).hasToString("150.00");
        assertThat(MoneyRules.scaled(new BigDecimal("500.0000"), "JPY")).hasToString("500");
        assertThat(MoneyRules.scaled(null, "INR")).isNull();
    }
}
