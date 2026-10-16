package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretFilesTest {
    @TempDir
    Path temp;

    Path secret(String content, String permissions) throws IOException {
        Path file = temp.resolve("test-only-secret.txt");
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }

    @Test
    void privateSingleTokenIsReadWithoutMarkdownSyntax() throws IOException {
        assertEquals("TEST_ONLY_SECRET", SecretFiles.read(secret("TEST_ONLY_SECRET\n", "rw-------")));
    }

    @Test
    void missingAndUnsafePermissionsAreRejected() throws IOException {
        assertThrows(IOException.class, () -> SecretFiles.read(temp.resolve("missing")));
        assertThrows(IOException.class, () -> SecretFiles.read(secret("TEST_ONLY_SECRET", "rw-r--r--")));
    }

    @Test
    void emptyMultipleTokensOversizeAndSymlinksAreRejected() throws IOException {
        assertThrows(IOException.class, () -> SecretFiles.read(secret("", "rw-------")));
        assertThrows(IOException.class, () -> SecretFiles.read(secret("secret: TEST_ONLY_SECRET", "rw-------")));
        assertThrows(IOException.class, () -> SecretFiles.read(secret("x".repeat(4097), "rw-------")));
        var file = secret("TEST_ONLY_SECRET", "rw-------");
        var link = temp.resolve("link");
        Files.createSymbolicLink(link, file);
        assertThrows(IOException.class, () -> SecretFiles.read(link));
    }
}
