package com.paisatrack.modules.transaction.dto;

import jakarta.validation.constraints.Size;

public record VoidRequest(@Size(max = 200) String reason) {
}
