package com.paisatrack.modules.category;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByIdAndUserId(UUID id, UUID userId);

    List<Category> findByUserIdOrderBySortOrderAscNameAsc(UUID userId);

    List<Category> findByUserIdAndParentIdIsNull(UUID userId);

    List<Category> findByUserIdAndParentId(UUID userId, UUID parentId);

    List<Category> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    boolean existsByParentId(UUID parentId);

    Optional<Category> findByUserIdAndSystemCode(UUID userId, String systemCode);
}
