package com.paisatrack.modules.debt;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DebtRepaymentRepository extends JpaRepository<DebtRepayment, UUID> {

    Optional<DebtRepayment> findByIdAndUserId(UUID id, UUID userId);

    List<DebtRepayment> findByDebtIdOrderByRepaymentDateAscCreatedAtAsc(UUID debtId);

    /** [debtId, sum of active repayments, count, latest repayment date] per debt. */
    @Query("""
            select r.debtId, sum(r.amount), count(r), max(r.repaymentDate) from DebtRepayment r
            where r.debtId in :debtIds and r.voidedAt is null
            group by r.debtId""")
    List<Object[]> totalsFor(@Param("debtIds") Collection<UUID> debtIds);

    boolean existsByDebtIdAndVoidedAtIsNull(UUID debtId);
}
