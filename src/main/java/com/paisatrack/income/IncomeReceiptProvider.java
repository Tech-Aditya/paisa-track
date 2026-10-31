package com.paisatrack.income;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Income actually received (active INCOME transactions) per source and pay period.
 * Implemented by the transaction module.
 */
public interface IncomeReceiptProvider {

    /** Keyed by source id, then by pay-period month (first day). Months with nothing received are absent. */
    Map<UUID, Map<LocalDate, BigDecimal>> receivedByPeriod(UUID userId, LocalDate fromMonth, LocalDate toMonth);
}
