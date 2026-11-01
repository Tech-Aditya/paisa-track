package com.paisatrack.modules.currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * A source of daily reference rates. Optional: when no provider bean exists, only manual rates are
 * used. Implementations must not hardcode rates.
 */
public interface ExchangeRateProvider {

    /** Stored as the rate's source, e.g. "FRANKFURTER". */
    String name();

    /** The latest published rates: one {@code base} buys {@code rates.get(quote)} of each quote. */
    Rates latest();

    record Rates(String base, LocalDate date, Map<String, BigDecimal> rates) {
    }
}
