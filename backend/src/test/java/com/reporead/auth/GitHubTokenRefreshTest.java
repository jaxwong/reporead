package com.reporead.auth;

import com.reporead.TestEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Renewing an expired GitHub App user token. Every token and response is test-only, shaped like GitHub's; no live call. */
@SpringBootTest
@AutoConfigureMockMvc
class GitHubTokenRefreshTest {
    private static final String TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";
    private static final String INSTALLATIONS = "https://api.github.com/user/installations?per_page=100";
    private static final String NO_INSTALLATIONS = "{\"total_count\":0,\"installations\":[]}";
    // GitHub answers a refused refresh with HTTP 200 and an error body (checked against github.com on 2026-10-13).
    private static final String REFUSED = "{\"error\":\"bad_refresh_token\",\"error_description\":\"The refresh token passed is incorrect or expired.\"}";

    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }

    @Autowired MockMvc mvc;
    @Autowired @Qualifier("githubUserApi") RestTemplate githubUserApi;
    @Autowired RestClientRefreshTokenTokenResponseClient githubRefreshTokens;
    @Autowired JdkClientHttpRequestFactory githubRequestFactory;
    @Autowired MeterRegistry meters;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired AppSessions sessions;
    @Autowired JdbcClient db;
    MockRestServiceServer api;
    MockRestServiceServer tokenEndpoint;
    String alice;

    @BeforeEach void setup() {
        TestEnvironment.reset(db);
        api = MockRestServiceServer.bindTo(githubUserApi).ignoreExpectOrder(true).build();
        var tokenClient = RestClient.builder();
        tokenEndpoint = MockRestServiceServer.bindTo(tokenClient).build();
        githubRefreshTokens.setRestClient(GitHubSecurity.tokenApi(tokenClient, meters));
        alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_UNUSED_TOKEN");
        TestSessions.saveGitHubToken(clients, registrations, 42, "TEST_ONLY_EXPIRED_TOKEN", Instant.now().minusSeconds(1), "TEST_ONLY_REFRESH_1");
    }

    @AfterEach void verifyNoExtraCalls() {
        api.verify();
        tokenEndpoint.verify();
        githubRefreshTokens.setRestClient(GitHubSecurity.tokenApi(RestClient.builder().requestFactory(githubRequestFactory), meters));
        clients.removeAuthorizedClient("github", "42");
    }

    private void next() {
        tokenEndpoint.verify();
        tokenEndpoint.reset();
        api.verify();
        api.reset();
    }

    private ResultActions available() throws Exception {
        return mvc.perform(get("/api/repositories/available").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice));
    }

    private void expectRefresh(ExpectedCount count, String refreshToken, ResponseCreator response) {
        tokenEndpoint.expect(count, requestTo(TOKEN_ENDPOINT)).andExpect(method(HttpMethod.POST))
            .andExpect(content().formDataContains(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken,
                "client_id", "test-only-not-a-github-app", "client_secret", "TEST_ONLY_NOT_A_REAL_SECRET")))
            .andRespond(response);
    }

    private static ResponseCreator renewed(String token, String refreshToken, int expiresIn) {
        return withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + ",\"refresh_token\":\"" + refreshToken +
            "\",\"refresh_token_expires_in\":15724800,\"token_type\":\"bearer\",\"scope\":\"\"}", MediaType.APPLICATION_JSON);
    }

    private void expectListing(ExpectedCount count, String token) {
        api.expect(count, requestTo(INSTALLATIONS)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andRespond(withSuccess(NO_INSTALLATIONS, MediaType.APPLICATION_JSON));
    }

    @Test void expiredTokenIsRenewedOnceAndTheRotatedRefreshTokenIsUsedNext() throws Exception {
        // Expiring in 30 seconds is inside the one-minute margin, so the second request renews again with GitHub's new refresh token.
        expectRefresh(ExpectedCount.once(), "TEST_ONLY_REFRESH_1", renewed("TEST_ONLY_TOKEN_2", "TEST_ONLY_REFRESH_2", 30));
        expectRefresh(ExpectedCount.once(), "TEST_ONLY_REFRESH_2", renewed("TEST_ONLY_TOKEN_3", "TEST_ONLY_REFRESH_3", 28800));
        expectListing(ExpectedCount.once(), "TEST_ONLY_TOKEN_2");
        expectListing(ExpectedCount.twice(), "TEST_ONLY_TOKEN_3");
        for (int run = 0; run < 3; run++) {
            available().andExpect(status().isOk()).andExpect(MockMvcResultMatchers.content().string(not(containsString("TEST_ONLY"))));
        }
    }

    @Test void refusedRenewalRequiresSignInAndIsNotAttemptedAgain() throws Exception {
        expectRefresh(ExpectedCount.once(), "TEST_ONLY_REFRESH_1", withSuccess(REFUSED, MediaType.APPLICATION_JSON));
        available().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
        available().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    }

    @Test void rejectedRenewalRequestRequiresSignIn() throws Exception {
        expectRefresh(ExpectedCount.once(), "TEST_ONLY_REFRESH_1", withBadRequest()
            .body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));
        available().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    }

    @ParameterizedTest @ValueSource(ints = {429, 502, 503})
    void unansweredRenewalKeepsTheRefreshTokenForTheNextRequest(int failure) throws Exception {
        expectRefresh(ExpectedCount.once(), "TEST_ONLY_REFRESH_1", withStatus(HttpStatus.valueOf(failure)));
        available().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
        next();
        expectRefresh(ExpectedCount.once(), "TEST_ONLY_REFRESH_1", renewed("TEST_ONLY_TOKEN_2", "TEST_ONLY_REFRESH_2", 28800));
        expectListing(ExpectedCount.once(), "TEST_ONLY_TOKEN_2");
        available().andExpect(status().isOk());
    }

    @Test void renewalTimeoutKeepsTheRefreshTokenForTheNextRequest() throws Exception {
        expectRefresh(ExpectedCount.once(), "TEST_ONLY_REFRESH_1", withException(new SocketTimeoutException("TEST_ONLY_TIMEOUT")));
        available().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
        next();
        expectRefresh(ExpectedCount.once(), "TEST_ONLY_REFRESH_1", renewed("TEST_ONLY_TOKEN_2", "TEST_ONLY_REFRESH_2", 28800));
        expectListing(ExpectedCount.once(), "TEST_ONLY_TOKEN_2");
        available().andExpect(status().isOk());
    }

    @Test void concurrentRequestsRenewOnceBecauseGitHubRotatesTheRefreshToken() throws Exception {
        expectRefresh(ExpectedCount.once(), "TEST_ONLY_REFRESH_1", renewed("TEST_ONLY_TOKEN_2", "TEST_ONLY_REFRESH_2", 28800));
        expectListing(ExpectedCount.times(4), "TEST_ONLY_TOKEN_2");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = IntStream.range(0, 4)
                .mapToObj(run -> executor.submit(() -> available().andExpect(status().isOk()))).toList();
            for (var request : requests) request.get(10, TimeUnit.SECONDS);
        }
    }
}
