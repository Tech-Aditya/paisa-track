package com.paisatrack.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.paisatrack.support.IntegrationTest;

class FlywayMigrationIntegrationTest extends IntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allMigrationsApplySuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();
        assertThat(Arrays.stream(applied).map(MigrationInfo::getState)).containsOnly(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void identityTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("users", "user_preferences", "refresh_tokens", "flyway_schema_history");
    }

    @Test
    void databaseRejectsNonNormalisedEmails() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into users (id, email, password_hash, full_name) values (?, ?, 'x', 'Name')",
                UUID.randomUUID(), "Mixed.Case@Example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsInvalidPreferenceValues() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, full_name) values (?, ?, 'x', 'Name')",
                userId, "constraint-" + userId + "@example.com");

        assertThatThrownBy(() -> jdbc.update(
                "insert into user_preferences (user_id, default_currency) values (?, 'inr')", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into user_preferences (user_id, week_start_day) values (?, 'FUNDAY')", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
