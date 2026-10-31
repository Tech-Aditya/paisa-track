package com.paisatrack.modules.account;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Debt outside accounts that reduces net worth, per currency (e.g. outstanding loan principal).
 * Implemented by the modules that own it, so accounts don't depend on them.
 */
public interface LiabilityProvider {

    Map<String, BigDecimal> outstandingByCurrency(UUID userId);
}
