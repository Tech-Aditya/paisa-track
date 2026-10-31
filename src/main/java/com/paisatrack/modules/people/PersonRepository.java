package com.paisatrack.modules.people;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PersonRepository extends JpaRepository<Person, UUID> {

    Optional<Person> findByIdAndUserId(UUID id, UUID userId);

    Optional<Person> findByUserIdAndNameIgnoreCase(UUID userId, String name);

    List<Person> findByUserIdOrderByNameAsc(UUID userId);

    List<Person> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(UUID userId, String name, UUID id);
}
