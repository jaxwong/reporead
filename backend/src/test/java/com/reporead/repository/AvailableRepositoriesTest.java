package com.reporead.repository;

import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.GitHubSecurity;
import com.reporead.auth.TestSessions;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AvailableRepositoriesTest {
    // All identities, repositories, and bearer values below are test-only, never a live integration fallback.
    private static final String ROUTE = "/api/repositories/available";
    private static final String INSTALLATIONS = "https://api.github.com/user/installations?per_page=100";
    private static final String UPSTREAM = "https://api.github.com/user/installations/7/repositories?per_page=100";
    private static final String TOKEN = "TEST_ONLY_ALICE_USER_TOKEN";
    private static final String ONE_INSTALLATION = "{\"total_count\":1,\"installations\":[{\"id\":7}]}";
    private static final String REPOSITORY = "{\"id\":11,\"full_name\":\"test-only/notes\",\"private\":true,\"default_branch\":\"main\",\"temp_clone_token\":\"TEST_ONLY_MUST_NOT_LEAK\"}";
    private static final String PAGE = "{\"total_count\":1,\"repositories\":[" + REPOSITORY + "]}";
    private static final String EMPTY = "{\"total_count\":0,\"repositories\":[]}";

    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }

    @Autowired MockMvc mvc;
    @Autowired @Qualifier("githubUserApi") RestTemplate githubUserApi;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired AppSessions sessions;
    @Autowired JdbcClient db;
    MockRestServiceServer server;
    String alice;

    @BeforeEach void setup() {
        TestEnvironment.reset(db);
        server = MockRestServiceServer.bindTo(githubUserApi).ignoreExpectOrder(true).build();
        alice = TestSessions.signIn(sessions, clients, registrations, 42, TOKEN);
    }

    @AfterEach void verifyNoExtraCalls() {
        server.verify();
        server.reset();
        clients.removeAuthorizedClient("github", "42");
        clients.removeAuthorizedClient("github", "84");
    }

    private void next() {
        server.verify();
        server.reset();
    }

    private ResultActions as(String bearer, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private ResultActions available(String bearer) throws Exception {
        return as(bearer, get(ROUTE));
    }

    private void expect(String url, String token, ResponseCreator response) {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
            .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
            .andRespond(response);
    }

    /** One installation, then its repositories: the two calls a normal listing makes. */
    private void expectListing(String token, ResponseCreator repositories) {
        expect(INSTALLATIONS, token, withSuccess(ONE_INSTALLATION, MediaType.APPLICATION_JSON));
        expect(UPSTREAM, token, repositories);
    }

    @Test void userTokenReturnsOnlySafeRepositoryFieldsAndSecondRequestWorks() throws Exception {
        expectListing(TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expectListing(TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        for (int run = 0; run < 2; run++) {
            available(alice).andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].githubRepositoryId").value(11))
                .andExpect(jsonPath("$.repositories[0].installationId").value(7))
                .andExpect(jsonPath("$.repositories[0].fullName").value("test-only/notes"))
                .andExpect(jsonPath("$.repositories[0].privateRepository").value(true))
                .andExpect(jsonPath("$.repositories[0].defaultBranch").value("main"))
                .andExpect(jsonPath("$.repositories[0].connectionId").doesNotExist())
                .andExpect(content().string(not(containsString("TOKEN"))))
                .andExpect(content().string(not(containsString("MUST_NOT_LEAK"))));
        }
    }

    @Test void noInstallationsAndEmptyListsAreCompleteNotSeeded() throws Exception {
        expect(INSTALLATIONS, TOKEN, withSuccess("{\"total_count\":0,\"installations\":[]}", MediaType.APPLICATION_JSON));
        available(alice).andExpect(status().isOk()).andExpect(jsonPath("$.repositories").isEmpty());
        server.verify();
        server.reset();
        expectListing(TOKEN, withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        available(alice).andExpect(status().isOk()).andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test void moreThanTenInstallationsFailWithoutListingRepositories() throws Exception {
        var many = new StringBuilder("{\"total_count\":11,\"installations\":[");
        for (int id = 1; id <= 11; id++) many.append(id == 1 ? "" : ",").append("{\"id\":").append(id).append('}');
        expect(INSTALLATIONS, TOKEN, withSuccess(many.append("]}").toString(), MediaType.APPLICATION_JSON));
        available(alice).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INSTALLATION_LIMIT"));
    }

    @Test void unauthenticatedAndUsersWithoutAGitHubTokenMakeNoExternalCalls() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized());
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        clients.removeAuthorizedClient("github", "84");
        available(bob).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    }

    @Test void concurrentUsersDoNotShareBearerTokens() throws Exception {
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        expectListing(TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expectListing("TEST_ONLY_BOB_USER_TOKEN", withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> available(alice).andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].fullName").value("test-only/notes")));
            var b = executor.submit(() -> available(bob).andExpect(status().isOk()).andExpect(jsonPath("$.repositories").isEmpty()));
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void callerCannotChooseTheGitHubPageSize() throws Exception {
        expectListing(TOKEN, withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        as(alice, get(ROUTE).param("per_page", "1").param("limit", "1000000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test void expiredGitHubTokenMakesNoExternalCalls() throws Exception {
        TestSessions.saveGitHubToken(clients, registrations, 42, TOKEN, Instant.now().minusSeconds(1));
        available(alice).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    }

    @ParameterizedTest @ValueSource(ints = {403, 404})
    void deniedOrUnknownInstallationHasNoFallback(int status) throws Exception {
        expectListing(TOKEN, withStatus(HttpStatus.valueOf(status)));
        available(alice).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GITHUB_ACCESS_DENIED"));
    }

    @Test void rejectedTokenRequiresANewSignInWithoutRefresh() throws Exception {
        expect(INSTALLATIONS, TOKEN, withUnauthorizedRequest());
        available(alice).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    }

    @ParameterizedTest @ValueSource(ints = {429, 500, 503})
    void rateLimitOrServerFailurePublishesNoRepositories(int status) throws Exception {
        expectListing(TOKEN, withStatus(HttpStatus.valueOf(status)));
        available(alice).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE")).andExpect(jsonPath("$.repositories").doesNotExist());
    }

    @Test void timeoutIsVisibleAndIsNotRetried() throws Exception {
        expect(INSTALLATIONS, TOKEN, withException(new SocketTimeoutException("TEST_ONLY_TIMEOUT")));
        available(alice).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
    }

    @ParameterizedTest @ValueSource(strings = {"X-RateLimit-Remaining", "Retry-After"})
    void github403RateLimitIsNotReportedAsMissingAccess(String header) throws Exception {
        expectListing(TOKEN, withForbiddenRequest().header(header, "0"));
        available(alice).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
    }

    @Test void upstreamErrorBodiesAreNotExposed() throws Exception {
        expectListing(TOKEN, withForbiddenRequest().body("TEST_ONLY_PRIVATE_ERROR_BODY"));
        available(alice).andExpect(status().isForbidden()).andExpect(content().string(not(containsString("PRIVATE_ERROR_BODY"))));
    }

    @Test void incompleteOrOverLimitPageIsNotPublishedOrFollowed() throws Exception {
        expectListing(TOKEN, withSuccess("{\"total_count\":101,\"repositories\":[" + REPOSITORY + "]}", MediaType.APPLICATION_JSON));
        available(alice).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_REPOSITORY_LIMIT"));
    }

    @ParameterizedTest @ValueSource(strings = {
        "not-json", "{}", "{\"repositories\":[]}", "{\"total_count\":1,\"repositories\":[]}",
        "{\"total_count\":0,\"repositories\":[]} trailing-junk", "{\"total_count\":1,\"repositories\":[null]}",
        "{\"total_count\":0,\"repositories\":null}",
        "{\"total_count\":1,\"repositories\":[{\"id\":1,\"full_name\":\"test-only/notes\",\"default_branch\":\"main\"}]}",
        "{\"total_count\":1,\"repositories\":[{\"id\":1.5,\"full_name\":\"test-only/notes\",\"private\":false,\"default_branch\":\"main\"}]}",
        "{\"total_count\":1,\"repositories\":[{\"id\":1,\"full_name\":\"test-only/notes\",\"private\":false}]}",
        "{\"total_count\":1,\"repositories\":[{\"id\":1,\"full_name\":\"../notes\",\"private\":false,\"default_branch\":\"main\"}]}"
    })
    void malformedOrPartialResponsesAreRejected(String json) throws Exception {
        expectListing(TOKEN, withSuccess(json, MediaType.APPLICATION_JSON));
        available(alice).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
    }

    @Test void duplicateRepositoryIdsAndOversizedResponsesAreRejected() throws Exception {
        expectListing(TOKEN, withSuccess("{\"total_count\":2,\"repositories\":[" + REPOSITORY + "," + REPOSITORY + "]}", MediaType.APPLICATION_JSON));
        available(alice).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
        server.verify();
        server.reset();
        expectListing(TOKEN, withSuccess(new byte[GitHubSecurity.MAX_RESPONSE_BYTES + 1], MediaType.APPLICATION_JSON));
        available(alice).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_RESPONSE_LIMIT"));
    }

    @Test void redirectsAreNotFollowed() throws Exception {
        expectListing(TOKEN, withStatus(HttpStatus.FOUND).location(java.net.URI.create("https://example.invalid")));
        available(alice).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
    }

    @Test void missingBodyIsNotAnEmptyRepositoryList() throws Exception {
        expectListing(TOKEN, withNoContent());
        available(alice).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
    }

    private ResultActions connect(String bearer, long githubRepositoryId, String body) throws Exception {
        return as(bearer, post("/api/repositories/" + githubRepositoryId + "/connect").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test void connectVerifiesEligibilityWithGitHubAndIsIdempotent() throws Exception {
        expect(UPSTREAM, TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        expect(UPSTREAM, TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        String first = connect(alice, 11, "{\"installationId\":7}").andExpect(status().isOk())
            .andExpect(jsonPath("$.fullName").value("test-only/notes")).andExpect(jsonPath("$.documentCount").value(0))
            .andExpect(jsonPath("$.lastSyncedCommitSha").doesNotExist()).andReturn().getResponse().getContentAsString();
        String second = connect(alice, 11, "{\"installationId\":7}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        assertEquals(1, db.sql("select count(*) from repository_connections").query(Integer.class).single());
        as(alice, get("/api/repositories")).andExpect(status().isOk()).andExpect(jsonPath("$.repositories.length()").value(1));

        next();
        expectListing(TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        available(alice).andExpect(jsonPath("$.repositories[0].connectionId").value(1));
    }

    @Test void unauthorizedRepositoryIsNotConnectedAndInvalidInputMakesNoCalls() throws Exception {
        expect(UPSTREAM, TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        connect(alice, 999, "{\"installationId\":7}").andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_AUTHORIZED"));
        connect(alice, 11, "{}").andExpect(status().isBadRequest());
        connect(alice, 0, "{\"installationId\":7}").andExpect(status().isBadRequest());
        assertEquals(0, db.sql("select count(*) from repository_connections").query(Integer.class).single());
    }

    @Test void anotherUsersConnectionsAreNotListed() throws Exception {
        expect(UPSTREAM, TOKEN, withSuccess(PAGE, MediaType.APPLICATION_JSON));
        connect(alice, 11, "{\"installationId\":7}").andExpect(status().isOk());
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        as(bob, get("/api/repositories")).andExpect(status().isOk()).andExpect(jsonPath("$.repositories").isEmpty());
    }
}
