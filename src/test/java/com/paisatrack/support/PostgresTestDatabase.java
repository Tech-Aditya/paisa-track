package com.paisatrack.support;

import java.io.IOException;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL for the whole test JVM: Testcontainers if Docker is up, else embedded PostgreSQL 18.
 * Force with {@code -Dpaisatrack.test.db=testcontainers|embedded} or {@code PAISATRACK_TEST_DB}.
 */
public final class PostgresTestDatabase {

    private static final Logger log = LoggerFactory.getLogger(PostgresTestDatabase.class);

    public record ConnectionInfo(String jdbcUrl, String username, String password) {
    }

    private static ConnectionInfo connection;

    private PostgresTestDatabase() {
    }

    public static synchronized ConnectionInfo get() {
        if (connection == null) {
            connection = useTestcontainers() ? startContainer() : startEmbedded();
        }
        return connection;
    }

    private static boolean useTestcontainers() {
        String mode = System.getProperty("paisatrack.test.db",
                System.getenv().getOrDefault("PAISATRACK_TEST_DB", "auto"));
        return switch (mode) {
            case "testcontainers" -> true;
            case "embedded" -> false;
            default -> DockerClientFactory.instance().isDockerAvailable();
        };
    }

    private static ConnectionInfo startContainer() {
        log.info("Test database: Testcontainers postgres:18-alpine");
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        container.start();
        return new ConnectionInfo(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static ConnectionInfo startEmbedded() {
        log.info("Test database: embedded PostgreSQL (Docker not available)");
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // best effort on JVM exit
                }
            }));
            return new ConnectionInfo(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
        } catch (IOException e) {
            throw new IllegalStateException("Could not start embedded PostgreSQL", e);
        }
    }
}
