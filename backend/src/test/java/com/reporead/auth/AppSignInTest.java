package com.reporead.auth;

import com.reporead.TestEnvironment;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AppSignInTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }

    @Autowired MockMvc mvc;
    @Autowired AppSessions sessions;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired @Qualifier("githubUserApi") RestTemplate githubUserApi;
    MockRestServiceServer github;

    @BeforeEach void reset() {
        TestEnvironment.reset(db);
        github = MockRestServiceServer.bindTo(githubUserApi).build();
    }

    @AfterEach void verifyGitHubCalls() {
        github.verify();
    }

    private static OAuth2AuthenticationToken github(long id, String login) {
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), Map.of("id", id, "login", login), "id");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "github");
    }

    /** Runs the real success handler as the OAuth callback would, returning the redirect target. */
    private String completeGitHubLogin(String challenge, long githubUserId, String login) throws Exception {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        if (challenge != null) session.setAttribute(AppAuthController.CHALLENGE_ATTRIBUTE, challenge);
        request.setSession(session);
        var response = new MockHttpServletResponse();
        new AppSignInSuccess(sessions).onAuthenticationSuccess(request, response, github(githubUserId, login));
        assertTrue(session.isInvalid(), "browser session must end once the app code is issued");
        return response.getRedirectedUrl();
    }

    private String code(String redirect) {
        assertTrue(redirect.startsWith(AppSignInSuccess.APP_REDIRECT), redirect);
        return redirect.substring(AppSignInSuccess.APP_REDIRECT.length());
    }

    private org.springframework.test.web.servlet.ResultActions exchange(String code, String verifier) throws Exception {
        return mvc.perform(post("/api/app-auth/token").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("code", code, "codeVerifier", verifier))));
    }

    private String accessToken(String code, String verifier) throws Exception {
        String body = exchange(code, verifier).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").stringValue();
    }

    @Test void signInStartStoresAValidChallengeAndRejectsOthers() throws Exception {
        String challenge = TestSessions.challenge(TestSessions.verifier());
        var result = mvc.perform(get("/app/sign-in").param("code_challenge", challenge))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/oauth2/authorization/github")).andReturn();
        assertEquals(challenge, result.getRequest().getSession().getAttribute(AppAuthController.CHALLENGE_ATTRIBUTE));
        mvc.perform(get("/app/sign-in").param("code_challenge", "too-short"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CODE_CHALLENGE"));
        mvc.perform(get("/app/sign-in")).andExpect(status().isBadRequest());
    }

    @Test void fullHandoffIssuesASessionThatAuthenticatesTheApiAndExposesNoGitHubToken() throws Exception {
        String verifier = TestSessions.verifier();
        String token = accessToken(code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice")), verifier);
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.githubUserId").value(42)).andExpect(jsonPath("$.login").value("test-only-alice"));
        assertEquals(0, db.sql("select count(*) from app_sign_in_codes").query(Integer.class).single());
        var stored = db.sql("select token_hash from app_sessions").query(byte[].class).single();
        assertNotEquals(token, new String(stored, java.nio.charset.StandardCharsets.US_ASCII), "only the hash is stored");
    }

    @Test void loginWithoutAnAppChallengeIssuesNoCode() throws Exception {
        assertEquals("/auth/failed", completeGitHubLogin(null, 42, "test-only-alice"));
        assertEquals(0, db.sql("select count(*) from app_sign_in_codes").query(Integer.class).single());
    }

    @Test void wrongVerifierConsumesTheCodeSoTheRightOneCannotFollow() throws Exception {
        String verifier = TestSessions.verifier();
        String code = code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice"));
        exchange(code, TestSessions.verifier()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIGN_IN_CODE"));
        exchange(code, verifier).andExpect(status().isBadRequest());
    }

    @Test void codeIsSingleUseAndExpires() throws Exception {
        String verifier = TestSessions.verifier();
        String code = code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice"));
        accessToken(code, verifier);
        exchange(code, verifier).andExpect(status().isBadRequest());

        String late = code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice"));
        db.sql("update app_sign_in_codes set expires_at = now() - interval '1 second'").update();
        exchange(late, verifier).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIGN_IN_CODE"));
    }

    @Test void concurrentExchangesOfOneCodeIssueExactlyOneSession() throws Exception {
        String verifier = TestSessions.verifier();
        String code = code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice"));
        Callable<Integer> attempt = () -> exchange(code, verifier).andReturn().getResponse().getStatus();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(attempt, attempt, attempt, attempt));
            int successes = 0;
            for (var result : results) if (result.get() == 200) successes++;
            assertEquals(1, successes);
        }
        assertEquals(1, db.sql("select count(*) from app_sessions").query(Integer.class).single());
    }

    @Test void malformedExchangeRequestsAreRejected() throws Exception {
        exchange("not-a-code", TestSessions.verifier()).andExpect(status().isBadRequest());
        mvc.perform(post("/api/app-auth/token").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/app-auth/token").contentType(MediaType.APPLICATION_JSON).content("not-json")).andExpect(status().isBadRequest());
    }

    @Test void secondSignInKeepsTheSameUserAndUpdatesTheLogin() throws Exception {
        String verifier = TestSessions.verifier();
        accessToken(code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-old")), verifier);
        String token = accessToken(code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-new")), verifier);
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(jsonPath("$.id").value(1)).andExpect(jsonPath("$.login").value("test-only-new"));
        assertEquals(1, db.sql("select count(*) from users").query(Integer.class).single());
    }

    @Test void invalidExpiredAndRevokedSessionsAreRejected() throws Exception {
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_GITHUB_TOKEN");
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.replace(alice.charAt(0), alice.charAt(0) == 'A' ? 'B' : 'A')))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Basic " + alice)).andExpect(status().isUnauthorized());

        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_GITHUB_TOKEN", withNoContent());
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob)).andExpect(status().isOk());

        db.sql("update app_sessions set expires_at = now() - interval '1 second'").update();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob)).andExpect(status().isUnauthorized());
    }

    @Test void signingOutTheLastSessionDeletesTheStoredGitHubToken() throws Exception {
        String phone = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        String tablet = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        String expired = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_GITHUB_TOKEN");
        db.sql("update app_sessions set expires_at = now() - interval '1 second' where token_hash = sha256(convert_to(:token, 'UTF8'))")
            .param("token", expired).update();

        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + phone)).andExpect(status().isNoContent());
        assertNotNull(clients.loadAuthorizedClient("github", "42"), "another device is still signed in");

        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_GITHUB_TOKEN", withNoContent());
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + tablet)).andExpect(status().isNoContent());
        assertNull(clients.loadAuthorizedClient("github", "42"), "an expired session does not keep the token");
        assertNotNull(clients.loadAuthorizedClient("github", "84"));
    }

    @ParameterizedTest @ValueSource(ints = {404, 422, 503})
    void signOutStillDeletesTheTokenWhenGitHubDoesNotRevokeIt(int failure) throws Exception {
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_GITHUB_TOKEN", withStatus(HttpStatus.valueOf(failure)));
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)).andExpect(status().isNoContent());
        assertNull(clients.loadAuthorizedClient("github", "42"));
    }

    @Test void signOutStillDeletesTheTokenWhenGitHubTimesOut() throws Exception {
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_GITHUB_TOKEN", withException(new SocketTimeoutException("TEST_ONLY_TIMEOUT")));
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)).andExpect(status().isNoContent());
        assertNull(clients.loadAuthorizedClient("github", "42"));
    }

    @Test void signOutWithoutAStoredGitHubTokenMakesNoGitHubCall() throws Exception {
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        clients.removeAuthorizedClient("github", "42");
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)).andExpect(status().isNoContent());
    }
}
