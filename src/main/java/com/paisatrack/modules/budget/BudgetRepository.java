package com.paisatrack.modules.budget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface BudgetRepository extends JpaRepository<Budget, UUID> {

    Optional<Budget> findByIdAndUserId(UUID id, UUID userId);

    List<Budget> findByUserIdOrderByCreatedAt(UUID userId);

    boolean existsByUserIdAndCategoryId(UUID userId, UUID categoryId);
}
