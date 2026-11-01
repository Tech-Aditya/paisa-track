package com.paisatrack.modules.attachment.dto;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(UUID id, UUID transactionId, String fileName, String contentType, long sizeBytes, Instant createdAt) {
}
