package com.paisatrack.modules.imports.dto;

import java.time.Instant;
import java.util.UUID;

public record ImportResult(UUID batchId, UUID accountId, String fileName, int imported, Instant createdAt) {
}
