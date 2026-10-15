package com.reporead;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Test-only configuration: a real PostgreSQL container shared by every Spring test, a fake OAuth client secret, and a
 * GitHub token key.
 */
public final class TestEnvironment {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    static {
        POSTGRES.start();
    }

    /** Test-only AES-256 key for stored GitHub tokens; never used outside tests. */
    public static final String TOKEN_KEY = "7e57".repeat(16);

    private TestEnvironment() {}

    public static void register(DynamicPropertyRegistry properties) {
        try {
            var secret = secretFile("reporead-test-client-secret-", "TEST_ONLY_NOT_A_REAL_SECRET");
            properties.add("reporead.github.client-secret-file", secret::toString);
            var key = secretFile("reporead-test-token-key-", TOKEN_KEY);
            properties.add("reporead.github.token-key-file", key::toString);
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
        properties.add("reporead.github.client-id", () -> "test-only-not-a-github-app");
        // The test-only GitHub users the suite signs in as.
        properties.add("reporead.allowed-github-user-ids", () -> "42,84");
        properties.add("reporead.public-url", () -> "http://127.0.0.1:8081");
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        // Spring keeps every test class's context (and its Hikari pool) cached, all sharing this one container's
        // 100 connections. Pools otherwise open their full 10 at startup, so 11 contexts exhausted the server;
        // with one idle connection each, a pool still grows to 10 when a concurrency test needs it.
        properties.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    /** A 0600 temporary file holding one test-only token, as the backend requires for its secrets. */
    public static Path secretFile(String prefix, String token) throws IOException {
        var file = Files.createTempFile(prefix, ".txt");
        Files.writeString(file, token + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        file.toFile().deleteOnExit();
        return file;
    }

    /** A new, empty test-only database in the shared container, for a test that runs the migrations itself. */
    public static DataSource emptyDatabase() {
        String name = "test_only_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create database " + name);
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot create the test-only database " + name, error);
        }
        return new DriverManagerDataSource(
                "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name,
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    /**
     * Waits until a session of the test database is blocked on a lock, so a concurrency test knows its request reached
     * the lock rather than assuming it did after a pause. Fails after 10 s.
     */
    public static void awaitLockWait(JdbcClient db) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (db.sql(
                                "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()")
                        .query(Integer.class)
                        .single()
                == 0) {
            if (System.nanoTime() > until) throw new AssertionError("No request waited for a lock within 10 s");
            Thread.sleep(20);
        }
    }

    public static void reset(JdbcClient db) {
        db.sql(
                        "truncate github_tokens, review_log, repository_images, annotation_mutations, annotation_locations, annotation_anchors, annotations, reading_states, documents, repository_connections, app_sessions, app_sign_in_codes, users restart identity")
                .update();
    }
}
