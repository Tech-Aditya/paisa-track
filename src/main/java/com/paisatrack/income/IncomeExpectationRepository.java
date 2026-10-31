package com.paisatrack.income;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface IncomeExpectationRepository extends JpaRepository<IncomeExpectation, UUID> {

    List<IncomeExpectation> findByIncomeSourceIdInOrderByEffectiveMonth(Collection<UUID> sourceIds);

    Optional<IncomeExpectation> findByIncomeSourceIdAndEffectiveMonth(UUID sourceId, LocalDate effectiveMonth);
}
