package com.paisatrack.user;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {
}
