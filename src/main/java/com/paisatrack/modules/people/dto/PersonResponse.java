package com.paisatrack.modules.people.dto;

import java.time.Instant;
import java.util.UUID;

public record PersonResponse(UUID id, String name, String phone, String email, String notes,
                             Instant createdAt, Instant updatedAt) {
}
