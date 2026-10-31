package com.paisatrack.modules.account;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Net money flow per account from active transactions: inflows minus outflows.
 * Implemented by the transaction module, so accounts don't depend on it.
 */
public interface AccountFlowProvider {

    /** Accounts without transactions are absent from the map. */
    Map<UUID, BigDecimal> netFlows(UUID userId);
}
