package com.paisatrack.modules.transaction;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** How many receipts each transaction has. Implemented by the attachment module. */
public interface AttachmentCounter {

    Map<UUID, Integer> counts(UUID userId, Collection<UUID> transactionIds);
}
