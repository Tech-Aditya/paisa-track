package com.paisatrack.modules.loan.dto;

import java.math.BigDecimal;
import java.util.List;

public record LoanPreviewResponse(BigDecimal emiAmount, int installments, BigDecimal totalInterest,
                                  BigDecimal totalPayment, List<InstallmentResponse> schedule) {
}
