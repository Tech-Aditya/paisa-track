package com.paisatrack.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StartupLoggerTest {

    @Test
    void databaseHostDropsCredentialsAndQueryParameters() {
        assertThat(StartupLogger.databaseHost("jdbc:postgresql://db.internal:5432/paisatrack?user=app&password=secret"))
                .isEqualTo("db.internal:5432/paisatrack");
        assertThat(StartupLogger.databaseHost("jdbc:postgresql://app:secret@db:5432/paisatrack"))
                .isEqualTo("db:5432/paisatrack");
        assertThat(StartupLogger.databaseHost("")).isEqualTo("unknown");
    }
}
