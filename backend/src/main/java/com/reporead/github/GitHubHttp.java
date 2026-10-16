package com.reporead.github;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * How RepoRead talks HTTP to GitHub: 15-second timeouts, no redirects, and a size ceiling on every response, which is
 * also where every GitHub request is counted. GitHubApi uses the three clients and GitHubCredentials the user API
 * client; sign-in (GitHubSecurity) uses the same request factory and ceiling.
 */
@Configuration
public class GitHubHttp {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubHttp.class);
    public static final String GITHUB_REQUESTS = "reporead.github.requests";
    /**
     * Ceiling for OAuth, metadata, and raw note responses, and so the reader's note limit
     * (MarkdownRenderer.MAX_NOTE_BYTES).
     */
    public static final int MAX_RESPONSE_BYTES = 1_048_576;
    /**
     * Above GitHub's documented 7 MB recursive-tree maximum, so only GitHub's own truncation can make a tree
     * incomplete.
     */
    public static final int MAX_TREE_RESPONSE_BYTES = 8 * 1_048_576;
    /** Repository images shown in notes; larger images are visibly unavailable rather than fetched. */
    public static final int MAX_IMAGE_RESPONSE_BYTES = 5 * 1_048_576;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    JdkClientHttpRequestFactory githubRequestFactory() {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requestFactory.setReadTimeout(HTTP_TIMEOUT);
        return requestFactory;
    }

    @Bean
    RestTemplate githubUserApi(JdkClientHttpRequestFactory githubRequestFactory, MeterRegistry meters) {
        return bounded(githubRequestFactory, MAX_RESPONSE_BYTES, meters);
    }

    @Bean
    RestTemplate githubTreeApi(JdkClientHttpRequestFactory githubRequestFactory, MeterRegistry meters) {
        return bounded(githubRequestFactory, MAX_TREE_RESPONSE_BYTES, meters);
    }

    @Bean
    RestTemplate githubImageApi(JdkClientHttpRequestFactory githubRequestFactory, MeterRegistry meters) {
        return bounded(githubRequestFactory, MAX_IMAGE_RESPONSE_BYTES, meters);
    }

    /** A client for GitHub whose responses are cut off at {@code maxBytes} and counted. */
    public static RestTemplate bounded(
            JdkClientHttpRequestFactory githubRequestFactory, int maxBytes, MeterRegistry meters) {
        var rest = new RestTemplate(githubRequestFactory);
        rest.setInterceptors(List.of(boundedResponse(maxBytes, meters)));
        return rest;
    }

    public static final class ResponseTooLarge extends IOException {
        ResponseTooLarge(String endpoint, int maxBytes) {
            super("GitHub response exceeds " + maxBytes + " bytes at " + endpoint);
        }
    }

    // OAuth login makes at most two application-level requests: token exchange, then user identity.
    // Every GitHub request passes here, so it is also where they are counted: reporead.github.requests by outcome
    // (status family, too_large, or failed). Tags never include paths, which name private repositories and notes.
    public static ClientHttpRequestInterceptor boundedResponse(int maxBytes, MeterRegistry meters) {
        return (request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.USER_AGENT, "RepoRead");
            request.getHeaders().set("X-GitHub-Api-Version", "2026-03-10");
            ClientHttpResponse response;
            try {
                response = execution.execute(request, body);
            } catch (IOException error) {
                meters.counter(GITHUB_REQUESTS, "outcome", "failed").increment();
                throw error;
            }
            byte[] bytes;
            try {
                bytes = response.getBody().readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new ResponseTooLarge(request.getURI().getPath(), maxBytes);
                }
            } catch (IOException error) {
                response.close();
                meters.counter(GITHUB_REQUESTS, "outcome", error instanceof ResponseTooLarge ? "too_large" : "failed")
                        .increment();
                LOG.warn(
                        "GitHub response failed; endpoint={} failureType={}",
                        request.getURI().getPath(),
                        error.getClass().getSimpleName());
                throw error;
            }
            meters.counter(GITHUB_REQUESTS, "outcome", (response.getStatusCode().value() / 100) + "xx")
                    .increment();
            LOG.info(
                    "GitHub response; endpoint={} status={} bytes={}",
                    request.getURI().getPath(),
                    response.getStatusCode().value(),
                    bytes.length);
            return new ClientHttpResponse() {
                private final InputStream buffered = new ByteArrayInputStream(bytes);

                @Override
                public HttpStatusCode getStatusCode() throws IOException {
                    return response.getStatusCode();
                }

                @Override
                public String getStatusText() throws IOException {
                    return response.getStatusText();
                }

                @Override
                public HttpHeaders getHeaders() {
                    return response.getHeaders();
                }

                @Override
                public InputStream getBody() {
                    return buffered;
                }

                @Override
                public void close() {
                    response.close();
                }
            };
        };
    }
}
