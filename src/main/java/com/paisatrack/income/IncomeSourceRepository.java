package com.paisatrack.income;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface IncomeSourceRepository extends JpaRepository<IncomeSource, UUID> {

    Optional<IncomeSource> findByIdAndUserId(UUID id, UUID userId);

    List<IncomeSource> findByUserIdOrderByCreatedAt(UUID userId);

    List<IncomeSource> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(UUID userId, String name, UUID id);

    boolean existsByUserIdAndCategoryId(UUID userId, UUID categoryId);
}
