package com.paisatrack.modules.debt.dto;

import jakarta.validation.constraints.Size;

/** Optional reason for a write-off or a voided repayment. */
public record ReasonRequest(@Size(max = 200) String reason) {
}
