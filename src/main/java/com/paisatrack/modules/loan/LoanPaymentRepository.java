package com.paisatrack.modules.loan;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LoanPaymentRepository extends JpaRepository<LoanPayment, UUID> {

    List<LoanPayment> findByLoanIdOrderByCreatedAtAsc(UUID loanId);

    @Query("select p from LoanPayment p where p.loanId in :loanIds and p.voidedAt is null")
    List<LoanPayment> findActiveByLoanIds(@Param("loanIds") Collection<UUID> loanIds);

    @Query("select count(p) > 0 from LoanPayment p where p.loanId = :loanId and p.voidedAt is null")
    boolean hasActive(@Param("loanId") UUID loanId);
}
