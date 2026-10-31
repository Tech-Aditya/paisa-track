package com.paisatrack.modules.recurring.dto;

import jakarta.validation.constraints.Size;

public record SkipOccurrenceRequest(@Size(max = 200) String reason) {
}
