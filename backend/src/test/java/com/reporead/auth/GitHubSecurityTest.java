package com.reporead.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GitHubSecurityTest {
    @TempDir Path temp;

    Path secret(String content, String permissions) throws IOException {
        Path file = temp.resolve("test-only-secret.txt");
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }

    @Test void privateSingleTokenIsReadWithoutMarkdownSyntax() throws IOException {
        assertEquals("TEST_ONLY_SECRET", GitHubSecurity.readSecret(secret("TEST_ONLY_SECRET\n", "rw-------")));
    }

    @Test void missingAndUnsafePermissionsAreRejected() throws IOException {
        assertThrows(IOException.class, () -> GitHubSecurity.readSecret(temp.resolve("missing")));
        assertThrows(IOException.class, () -> GitHubSecurity.readSecret(secret("TEST_ONLY_SECRET", "rw-r--r--")));
    }

    @Test void emptyMultipleTokensOversizeAndSymlinksAreRejected() throws IOException {
        assertThrows(IOException.class, () -> GitHubSecurity.readSecret(secret("", "rw-------")));
        assertThrows(IOException.class, () -> GitHubSecurity.readSecret(secret("secret: TEST_ONLY_SECRET", "rw-------")));
        assertThrows(IOException.class, () -> GitHubSecurity.readSecret(secret("x".repeat(4097), "rw-------")));
        var file = secret("TEST_ONLY_SECRET", "rw-------");
        var link = temp.resolve("link");
        Files.createSymbolicLink(link, file);
        assertThrows(IOException.class, () -> GitHubSecurity.readSecret(link));
    }

    @Test void oauthResponseHasAHardByteCapAndNeverRetriesAfterFailure() throws IOException {
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.github.com/user"));
        var calls = new AtomicInteger();
        var oversized = new MockClientHttpResponse(new byte[GitHubSecurity.MAX_RESPONSE_BYTES + 1], HttpStatus.OK);
        assertThrows(IOException.class, () -> GitHubSecurity.boundedResponse(GitHubSecurity.MAX_RESPONSE_BYTES).intercept(request, new byte[0], (sent, body) -> {
            calls.incrementAndGet(); return oversized;
        }));
        assertEquals(1, calls.get());
        assertEquals("RepoRead", request.getHeaders().getFirst("User-Agent"));
        assertEquals("2026-03-10", request.getHeaders().getFirst("X-GitHub-Api-Version"));
    }

    @Test void boundedResponsePreservesExpectedExternalFailureAndTheSecondCallWorks() throws IOException {
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.github.com/user"));
        var interceptor = GitHubSecurity.boundedResponse(GitHubSecurity.MAX_RESPONSE_BYTES);
        var denied = interceptor.intercept(request, new byte[0], (sent, body) ->
            new MockClientHttpResponse("test-only-denied".getBytes(StandardCharsets.UTF_8), HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("test-only-denied", new String(denied.getBody().readAllBytes(), StandardCharsets.UTF_8));
        denied.close();
        var success = interceptor.intercept(request, new byte[0], (sent, body) ->
            new MockClientHttpResponse("{}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
        assertEquals(HttpStatus.OK, success.getStatusCode());
        success.close();
    }

    @Test void transportTimeoutIsRaisedWithoutAnAlternateStrategy() {
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.github.com/user"));
        var calls = new AtomicInteger();
        assertThrows(java.net.SocketTimeoutException.class, () -> GitHubSecurity.boundedResponse(GitHubSecurity.MAX_RESPONSE_BYTES).intercept(request, new byte[0], (sent, body) -> {
            calls.incrementAndGet(); throw new java.net.SocketTimeoutException("test-only timeout");
        }));
        assertEquals(1, calls.get());
    }
}
