package com.reporead.github;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

@ExtendWith(OutputCaptureExtension.class)
class GitHubHttpConfigurationTest {
    /** GitHub requests counted with this outcome tag. */
    private static double requests(SimpleMeterRegistry meters, String outcome) {
        return meters.counter(GitHubHttpConfiguration.GITHUB_REQUESTS, "outcome", outcome)
                .count();
    }

    /**
     * Every GitHub request passes this interceptor. It sets RepoRead's headers, admits a response of exactly its
     * ceiling, passes an error status through, raises a transport failure or a response over the ceiling unchanged, and
     * counts each outcome once. No log line or message names the request's path, which can name a private note.
     */
    @Test
    void everyOutcomeIsCountedAndLoggedWithoutGitHubPaths(CapturedOutput log) throws IOException {
        var request = new MockClientHttpRequest(
                HttpMethod.GET, URI.create("https://api.github.com/repos/PRIVATE_OWNER/PRIVATE_NOTE/image.png"));
        var meters = new SimpleMeterRegistry();
        var interceptor = GitHubHttpConfiguration.boundedResponse(2, meters);
        interceptor
                .intercept(request, new byte[0], (sent, body) -> new MockClientHttpResponse(new byte[2], HttpStatus.OK))
                .close();
        var denied = interceptor.intercept(
                request, new byte[0], (sent, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        denied.close();
        assertThrows(
                SocketTimeoutException.class,
                () -> interceptor.intercept(request, new byte[0], (sent, body) -> {
                    throw new SocketTimeoutException("test-only timeout");
                }));
        var oversized = assertThrows(
                GitHubHttpConfiguration.ResponseTooLarge.class,
                () -> interceptor.intercept(
                        request, new byte[0], (sent, body) -> new MockClientHttpResponse(new byte[3], HttpStatus.OK)));

        assertEquals(1, requests(meters, "2xx"), "2xx");
        assertEquals(1, requests(meters, "4xx"), "4xx");
        assertEquals(1, requests(meters, "failed"), "failed");
        assertEquals(1, requests(meters, "too_large"), "too_large");
        assertEquals("RepoRead", request.getHeaders().getFirst("User-Agent"));
        assertEquals("2026-03-10", request.getHeaders().getFirst("X-GitHub-Api-Version"));
        assertTrue(log.getAll().contains("GitHub response"));
        assertTrue(log.getAll().contains("GitHub request failed; failureType=SocketTimeoutException"));
        assertTrue(log.getAll().contains("GitHub request failed; failureType=ResponseTooLarge"));
        assertFalse(log.getAll().contains("PRIVATE_"));
        assertFalse(oversized.getMessage().contains("PRIVATE_"));
    }
}
