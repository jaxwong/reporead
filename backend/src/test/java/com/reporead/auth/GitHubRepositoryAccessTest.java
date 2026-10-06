package com.reporead.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "reporead.github.client-id=test-only-not-a-github-app")
@AutoConfigureMockMvc
class GitHubRepositoryAccessTest {
    // All identities, repositories, and bearer values below are test-only, never a live integration fallback.
    private static final String ROUTE = "/api/auth/installations/7/repositories";
    private static final String UPSTREAM = "https://api.github.com/user/installations/7/repositories?per_page=100";
    private static final String TOKEN = "TEST_ONLY_ALICE_USER_TOKEN";
    private static final String REPOSITORY = "{\"id\":11,\"full_name\":\"test-only/notes\",\"private\":true,\"temp_clone_token\":\"TEST_ONLY_MUST_NOT_LEAK\"}";
    private static final String PAGE = "{\"total_count\":1,\"repositories\":[" + REPOSITORY + "]}";

    @DynamicPropertySource static void testSecret(DynamicPropertyRegistry properties) {
        GitHubLoginTest.testSecret(properties);
    }

    @Autowired MockMvc mvc;
    @Autowired RestTemplate githubUserApi;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    MockRestServiceServer server;

    @BeforeEach void setup() {
        server = MockRestServiceServer.bindTo(githubUserApi).ignoreExpectOrder(true).build();
        saveToken(42L, TOKEN, Instant.now().plusSeconds(600));
    }

    @AfterEach void verifyNoExtraCalls() {
        server.verify();
        server.reset();
        clients.removeAuthorizedClient("github", "42");
        clients.removeAuthorizedClient("github", "84");
    }

    private DefaultOAuth2User user(long id) {
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
            Map.of("id", id, "login", "test-only-user-" + id), "id");
    }

    private void saveToken(long id, String value, Instant expires) {
        var principal = user(id);
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value,
            Instant.now().minusSeconds(60), expires);
        clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registrations.findByRegistrationId("github"),
            principal.getName(), token), authentication);
    }

    private MockHttpServletRequestBuilder request(long id) {
        return get(ROUTE).with(oauth2Login().clientRegistration(registrations.findByRegistrationId("github")).oauth2User(user(id)));
    }

    private void expect(String token, ResponseCreator response) {
        server.expect(requestTo(UPSTREAM)).andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
            .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
            .andRespond(response);
    }

    @Test void userTokenReturnsOnlySafeRepositoryFieldsAndSecondRequestWorks() throws Exception {
        expect(TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expect(TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        for (int run = 0; run < 2; run++) {
            mvc.perform(request(42L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.installationId").value(7))
                .andExpect(jsonPath("$.repositories[0].id").value(11))
                .andExpect(jsonPath("$.repositories[0].fullName").value("test-only/notes"))
                .andExpect(jsonPath("$.repositories[0].privateRepository").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("TOKEN"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("MUST_NOT_LEAK"))));
        }
    }

    @Test void emptyListIsCompleteNotASeededRepository() throws Exception {
        expect(TOKEN, withSuccess("{\"total_count\":0,\"repositories\":[]}", MediaType.APPLICATION_JSON));
        mvc.perform(request(42L)).andExpect(status().isOk()).andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test void unauthenticatedUserAndDifferentUserCannotReuseAnotherUsersToken() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized());
        mvc.perform(request(84L)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    }

    @Test void eachSignedInUserUsesTheirOwnAuthorizedClient() throws Exception {
        saveToken(84L, "TEST_ONLY_BOB_USER_TOKEN", Instant.now().plusSeconds(600));
        expect("TEST_ONLY_BOB_USER_TOKEN", withSuccess("{\"total_count\":0,\"repositories\":[]}", MediaType.APPLICATION_JSON));
        mvc.perform(request(84L)).andExpect(status().isOk()).andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test void concurrentUsersDoNotShareBearerTokens() throws Exception {
        saveToken(84L, "TEST_ONLY_BOB_USER_TOKEN", Instant.now().plusSeconds(600));
        expect(TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expect("TEST_ONLY_BOB_USER_TOKEN", withSuccess("{\"total_count\":0,\"repositories\":[]}", MediaType.APPLICATION_JSON));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var alice = executor.submit(() -> mvc.perform(request(42L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].fullName").value("test-only/notes")));
            var bob = executor.submit(() -> mvc.perform(request(84L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").isEmpty()));
            alice.get(5, TimeUnit.SECONDS);
            bob.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void callerCannotChooseTheGitHubPageSize() throws Exception {
        expect(TOKEN, withSuccess("{\"total_count\":0,\"repositories\":[]}", MediaType.APPLICATION_JSON));
        mvc.perform(request(42L).param("per_page", "1").param("limit", "1000000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test void expiredTokenAndInvalidIdMakeNoExternalCalls() throws Exception {
        saveToken(42L, TOKEN, Instant.now().minusSeconds(1));
        mvc.perform(request(42L)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
        mvc.perform(get("/api/auth/installations/0/repositories").with(oauth2Login()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INSTALLATION_ID"));
        mvc.perform(get("/api/auth/installations/not-a-number/repositories").with(oauth2Login()))
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(ints = {403, 404})
    void deniedOrUnknownInstallationHasNoFallback(int status) throws Exception {
        expect(TOKEN, withStatus(HttpStatus.valueOf(status)));
        mvc.perform(request(42L)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GITHUB_ACCESS_DENIED"));
    }

    @Test void rejectedTokenRequiresANewSignInWithoutRefresh() throws Exception {
        expect(TOKEN, withUnauthorizedRequest());
        mvc.perform(request(42L)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    }

    @ParameterizedTest @ValueSource(ints = {429, 500, 503})
    void rateLimitOrServerFailurePublishesNoRepositories(int status) throws Exception {
        expect(TOKEN, withStatus(HttpStatus.valueOf(status)));
        mvc.perform(request(42L)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE")).andExpect(jsonPath("$.repositories").doesNotExist());
    }

    @Test void timeoutIsVisibleAndIsNotRetried() throws Exception {
        expect(TOKEN, withException(new SocketTimeoutException("TEST_ONLY_TIMEOUT")));
        mvc.perform(request(42L)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
    }

    @ParameterizedTest @ValueSource(strings = {"X-RateLimit-Remaining", "Retry-After"})
    void github403RateLimitIsNotReportedAsMissingAccess(String header) throws Exception {
        expect(TOKEN, withForbiddenRequest().header(header, "0"));
        mvc.perform(request(42L)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
    }

    @Test void upstreamErrorBodiesAreNotExposed() throws Exception {
        expect(TOKEN, withForbiddenRequest().body("TEST_ONLY_PRIVATE_ERROR_BODY"));
        mvc.perform(request(42L)).andExpect(status().isForbidden())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_ERROR_BODY"))));
    }

    @Test void incompleteOrOverLimitPageIsNotPublishedOrFollowed() throws Exception {
        expect(TOKEN, withSuccess("{\"total_count\":101,\"repositories\":[" + REPOSITORY + "]}", MediaType.APPLICATION_JSON));
        expect(TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON).header("Link", "<https://api.github.com/next>; rel=\"next\""));
        mvc.perform(request(42L)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_REPOSITORY_LIMIT"));
        mvc.perform(request(42L)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_REPOSITORY_LIMIT"));
    }

    @ParameterizedTest @ValueSource(strings = {
        "not-json", "{}", "{\"repositories\":[]}", "{\"total_count\":1,\"repositories\":[]}",
        "{\"total_count\":0,\"repositories\":[]} trailing-junk", "{\"total_count\":1,\"repositories\":[null]}",
        "{\"total_count\":0,\"repositories\":null}",
        "{\"total_count\":1,\"repositories\":[{\"id\":1,\"full_name\":\"test-only/notes\"}]}",
        "{\"total_count\":1,\"repositories\":[{\"id\":1.5,\"full_name\":\"test-only/notes\",\"private\":false}]}"
    })
    void malformedOrPartialResponsesAreRejected(String json) throws Exception {
        expect(TOKEN, withSuccess(json, MediaType.APPLICATION_JSON));
        mvc.perform(request(42L)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
    }

    @Test void duplicateRepositoryIdsAndOversizedResponsesAreRejected() throws Exception {
        expect(TOKEN, withSuccess("{\"total_count\":2,\"repositories\":[" + REPOSITORY + "," + REPOSITORY + "]}", MediaType.APPLICATION_JSON));
        expect(TOKEN, withSuccess(new byte[GitHubSecurity.MAX_OAUTH_RESPONSE_BYTES + 1], MediaType.APPLICATION_JSON));
        mvc.perform(request(42L)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
        mvc.perform(request(42L)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_RESPONSE_LIMIT"));
    }

    @Test void redirectsAreNotFollowed() throws Exception {
        expect(TOKEN, withStatus(HttpStatus.FOUND).location(java.net.URI.create("https://example.invalid")));
        mvc.perform(request(42L)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
    }

    @Test void missingBodyIsNotAnEmptyRepositoryList() throws Exception {
        expect(TOKEN, withNoContent());
        mvc.perform(request(42L)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
    }
}
