package com.paisatrack.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StartupLoggerTest {

    @Test
    void databaseHostDropsCredentialsAndQueryParameters() {
        assertThat(StartupLogger.databaseHost("jdbc:postgresql://db.internal:5432/paisatrack?user=app&password=secret"))
                .isEqualTo("db.internal:5432/paisatrack");
        assertThat(StartupLogger.databaseHost("jdbc:postgresql://app:secret@db:5432/paisatrack"))
                .isEqualTo("db:5432/paisatrack");
        assertThat(StartupLogger.databaseHost("")).isEqualTo("unknown");
    }

    @Test
    void summaryListsNameProfileAndAddresses() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.application.name", "paisatrack")
                .withProperty("paisatrack.version", "1.2.0")
                .withProperty("local.server.port", "9090")
                .withProperty("paisatrack.public-url", "https://paisa.example.com/")
                .withProperty("spring.datasource.url", "jdbc:postgresql://u:p@db:5432/paisatrack");
        env.setActiveProfiles("prod");

        assertThat(StartupLogger.summary(env))
                .startsWith("PaisaTrack ready: paisatrack 1.2.0")
                .contains("Profile    prod", "Local      http://localhost:9090/", "Web app    http://localhost:9090/app/",
                        "API docs   http://localhost:9090/swagger-ui.html", "Health     http://localhost:9090/actuator/health",
                        "Public     https://paisa.example.com/app/", "Database   db:5432/paisatrack")
                .doesNotContain("u:p");
    }
}
