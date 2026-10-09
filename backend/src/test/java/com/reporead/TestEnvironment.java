package com.reporead;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

/** Test-only configuration: a real PostgreSQL container shared by every Spring test, and a fake OAuth client secret. */
public final class TestEnvironment {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static { POSTGRES.start(); }

    private TestEnvironment() {}

    public static void register(DynamicPropertyRegistry properties) {
        try {
            var file = Files.createTempFile("reporead-test-client-secret-", ".txt");
            Files.writeString(file, "TEST_ONLY_NOT_A_REAL_SECRET\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            file.toFile().deleteOnExit();
            properties.add("reporead.github.client-secret-file", file::toString);
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
        properties.add("reporead.github.client-id", () -> "test-only-not-a-github-app");
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    public static void reset(JdbcClient db) {
        db.sql("truncate review_log, repository_images, annotation_mutations, annotation_locations, annotation_anchors, annotations, reading_states, documents, repository_connections, app_sessions, app_sign_in_codes, users restart identity").update();
    }
}
