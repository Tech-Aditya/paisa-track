package com.paisatrack.modules.loan;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, UUID> {

    List<LoanInstallment> findByLoanIdOrderByNumberAsc(UUID loanId);

    List<LoanInstallment> findByLoanIdInOrderByNumberAsc(Collection<UUID> loanIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LoanInstallment i where i.loanId = :loanId and i.number > :afterNumber")
    void deleteAfter(@Param("loanId") UUID loanId, @Param("afterNumber") int afterNumber);
}
