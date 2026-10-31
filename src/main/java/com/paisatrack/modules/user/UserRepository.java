package com.paisatrack.modules.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String normalizedEmail);

    boolean existsByEmail(String normalizedEmail);

    @Query("select u.id from User u where u.status = com.paisatrack.modules.user.UserStatus.ACTIVE order by u.id")
    List<UUID> findActiveIds();
}
