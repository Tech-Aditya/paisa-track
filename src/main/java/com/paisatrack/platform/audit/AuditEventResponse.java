package com.paisatrack.platform.audit;

import java.time.Instant;
import java.util.Map;

/** @param changes {field: {from, to}}; for CREATED, every field with {@code from} null */
public record AuditEventResponse(AuditAction action, Map<String, Object> changes, Instant occurredAt) {
}
