package com.paisatrack;

import org.springframework.boot.SpringApplication;

import com.paisatrack.support.PostgresTestDatabase;

/**
 * Runs the app against a throwaway PostgreSQL (Testcontainers, or embedded when Docker is missing).
 * Data is lost on exit. Start with:
 * {@code mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.paisatrack.LocalDevApplication}
 */
public final class LocalDevApplication {

    private LocalDevApplication() {
    }

    public static void main(String[] args) {
        PostgresTestDatabase.ConnectionInfo db = PostgresTestDatabase.get();
        System.setProperty("spring.datasource.url", db.jdbcUrl());
        System.setProperty("spring.datasource.username", db.username());
        System.setProperty("spring.datasource.password", db.password());
        SpringApplication.run(PaisaTrackApplication.class, args);
    }
}
