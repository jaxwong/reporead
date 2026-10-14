package com.reporead.github;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GitHubHttpTest {
    @Test void oauthResponseHasAHardByteCapAndNeverRetriesAfterFailure() throws IOException {
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.github.com/user"));
        var calls = new AtomicInteger();
        var oversized = new MockClientHttpResponse(new byte[GitHubHttp.MAX_RESPONSE_BYTES + 1], HttpStatus.OK);
        var meters = new SimpleMeterRegistry();
        assertThrows(IOException.class, () -> GitHubHttp.boundedResponse(GitHubHttp.MAX_RESPONSE_BYTES, meters).intercept(request, new byte[0], (sent, body) -> {
            calls.incrementAndGet(); return oversized;
        }));
        assertEquals(1, calls.get());
        assertEquals(1, meters.counter(GitHubHttp.GITHUB_REQUESTS, "outcome", "too_large").count());
        assertEquals("RepoRead", request.getHeaders().getFirst("User-Agent"));
        assertEquals("2026-03-10", request.getHeaders().getFirst("X-GitHub-Api-Version"));
    }

    @Test void boundedResponsePreservesExpectedExternalFailureAndTheSecondCallWorks() throws IOException {
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.github.com/user"));
        var meters = new SimpleMeterRegistry();
        var interceptor = GitHubHttp.boundedResponse(GitHubHttp.MAX_RESPONSE_BYTES, meters);
        var denied = interceptor.intercept(request, new byte[0], (sent, body) ->
            new MockClientHttpResponse("test-only-denied".getBytes(StandardCharsets.UTF_8), HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("test-only-denied", new String(denied.getBody().readAllBytes(), StandardCharsets.UTF_8));
        denied.close();
        var success = interceptor.intercept(request, new byte[0], (sent, body) ->
            new MockClientHttpResponse("{}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
        assertEquals(HttpStatus.OK, success.getStatusCode());
        success.close();
        assertEquals(1, meters.counter(GitHubHttp.GITHUB_REQUESTS, "outcome", "4xx").count());
        assertEquals(1, meters.counter(GitHubHttp.GITHUB_REQUESTS, "outcome", "2xx").count());
    }

    @Test void transportTimeoutIsRaisedWithoutAnAlternateStrategy() {
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.github.com/user"));
        var calls = new AtomicInteger();
        var meters = new SimpleMeterRegistry();
        assertThrows(java.net.SocketTimeoutException.class, () -> GitHubHttp.boundedResponse(GitHubHttp.MAX_RESPONSE_BYTES, meters).intercept(request, new byte[0], (sent, body) -> {
            calls.incrementAndGet(); throw new java.net.SocketTimeoutException("test-only timeout");
        }));
        assertEquals(1, calls.get());
        assertEquals(1, meters.counter(GitHubHttp.GITHUB_REQUESTS, "outcome", "failed").count());
    }
}
