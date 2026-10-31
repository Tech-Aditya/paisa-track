package com.paisatrack.transaction.dto;

import jakarta.validation.constraints.Size;

public record VoidRequest(@Size(max = 200) String reason) {
}
