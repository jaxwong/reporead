// Owns: Integration coverage of PKCE handoff, app sessions and sign-out behavior.

package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Owns integration coverage of PKCE handoff, app sessions and sign-out behavior. */
class AppSignInTest extends SharedSpringTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    AppSessions sessions;

    @Autowired
    GitHubTokenLifecycle credentials;

    @Autowired
    JdbcClient db;

    @Autowired
    JsonMapper json;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    @Qualifier("githubUserHttpClient")
    RestTemplate githubUserHttpClient;

    MockRestServiceServer github;

    /** A request authenticated before waiting cannot later remove a new sign-in's credentials. */
    @Test
    void aSessionChangeRechecksItsAdmissionAfterWaitingForTheLifecycle() throws Exception {
        String old = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_OLD_TOKEN");
        var user = sessions.authenticate(old).orElseThrow();
        var started = new CountDownLatch(1);
        var changed = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stale = credentials.changing(() -> {
                var request = executor.submit(() -> {
                    started.countDown();
                    return credentials.withSession(old, user, () -> {
                        changed.set(true);
                        return true;
                    });
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> request.get(300, TimeUnit.MILLISECONDS));
                sessions.revoke(old, user.id());
                TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_NEW_TOKEN");
                return request;
            });
            var failure = assertThrows(ExecutionException.class, () -> stale.get(5, TimeUnit.SECONDS));
            assertEquals(
                    com.reporead.ApiFailure.Code.SIGN_IN_REQUIRED,
                    assertInstanceOf(com.reporead.ApiFailure.class, failure.getCause()).code);
        }
        assertFalse(changed.get());
        assertEquals(
                "TEST_ONLY_NEW_TOKEN",
                clients.loadAuthorizedClient("github", "42").getAccessToken().getTokenValue());
    }

    /** A sign-out ordered after the callback must not let its old code issue a session with no GitHub credential. */
    @Test
    void lastDeviceSignOutInvalidatesAnUnexchangedCodeFromAnEarlierCallback() throws Exception {
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_TOKEN");
        String verifier = TestSessions.verifier();
        String redirect = completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-user");
        String code = redirect.substring(redirect.indexOf("?code=") + 6);
        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_TOKEN", withNoContent());
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNoContent());
        exchange(code, verifier)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGN_IN_CODE"));
    }

    @BeforeEach
    void reset() {
        TestEnvironment.reset(db);
        github = MockRestServiceServer.bindTo(githubUserHttpClient).build();
    }

    @AfterEach
    void verifyGitHubCalls() {
        github.verify();
    }

    private static OAuth2AuthenticationToken github(long id, String login) {
        var user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OAUTH2_USER")), Map.of("id", id, "login", login), "id");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "github");
    }

    /**
     * Runs the real success handler as the OAuth callback would on this test server, whose loopback address makes it
     * the debug app's, returning the redirect target.
     */
    private String completeGitHubLogin(String challenge, long githubUserId, String login) throws Exception {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        if (challenge != null) session.setAttribute(PendingAppSignIn.CHALLENGE_ATTRIBUTE, challenge);
        request.setSession(session);
        var response = new MockHttpServletResponse();
        new AppSignInSuccess(sessions, "reporead-dev://auth")
                .onAuthenticationSuccess(request, response, github(githubUserId, login));
        assertTrue(session.isInvalid(), "browser session must end once the app code is issued");
        return response.getRedirectedUrl();
    }

    private String code(String redirect) {
        String prefix = "reporead-dev://auth?code=";
        assertTrue(redirect.startsWith(prefix), redirect);
        return redirect.substring(prefix.length());
    }

    private org.springframework.test.web.servlet.ResultActions exchange(String code, String verifier) throws Exception {
        return mvc.perform(post("/api/app-auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code, "codeVerifier", verifier))));
    }

    private String accessToken(String code, String verifier) throws Exception {
        String body = exchange(code, verifier)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("accessToken").stringValue();
    }

    @Test
    void signInStartStoresAValidChallengeAndRefusesOthers() throws Exception {
        String challenge = TestSessions.challenge(TestSessions.verifier());
        var result = mvc.perform(get("/app/sign-in").param("code_challenge", challenge))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/github"))
                .andReturn();
        assertEquals(challenge, result.getRequest().getSession().getAttribute(PendingAppSignIn.CHALLENGE_ATTRIBUTE));
        var refused = mvc.perform(get("/app/sign-in").param("code_challenge", "too-short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_CHALLENGE"))
                .andReturn();
        assertNull(refused.getRequest().getSession(false));
        mvc.perform(get("/app/sign-in")).andExpect(status().isBadRequest());
    }

    @Test
    void fullHandoffIssuesASessionThatAuthenticatesTheApiAndExposesNoGitHubToken() throws Exception {
        String verifier = TestSessions.verifier();
        var body = new HashMap<String, Object>();
        body.put("code", code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice")));
        body.put("codeVerifier", verifier);
        String answer = mvc.perform(post("/api/app-auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = json.readTree(answer).get("accessToken").stringValue();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubUserId").value(42))
                .andExpect(jsonPath("$.login").value("test-only-alice"));
        assertEquals(
                0,
                db.sql("SELECT count(*) FROM app_sign_in_codes")
                        .query(Integer.class)
                        .single());
        var stored = db.sql("SELECT token_hash FROM app_sessions")
                .query(byte[].class)
                .single();
        assertNotEquals(
                token, new String(stored, java.nio.charset.StandardCharsets.US_ASCII), "only the hash is stored");
    }

    /**
     * GitHubLoginTest shows such a login cannot start; reaching the callback without the challenge is a broken
     * invariant.
     */
    @Test
    void loginWithoutAnAppChallengeIsAnInvariantFailureAndIssuesNoCode() {
        var request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        assertThrows(
                IllegalStateException.class,
                () -> new AppSignInSuccess(sessions, "reporead-dev://auth")
                        .onAuthenticationSuccess(
                                request, new MockHttpServletResponse(), github(42, "test-only-alice")));
        assertEquals(
                0,
                db.sql("SELECT count(*) FROM app_sign_in_codes")
                        .query(Integer.class)
                        .single());
        assertEquals(
                0, db.sql("SELECT count(*) FROM users").query(Integer.class).single());
    }

    @Test
    void wrongVerifierConsumesTheCodeSoTheRightOneCannotFollow() throws Exception {
        String verifier = TestSessions.verifier();
        String code = code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice"));
        exchange(code, TestSessions.verifier())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGN_IN_CODE"));
        exchange(code, verifier).andExpect(status().isBadRequest());
    }

    @Test
    void codeIsSingleUseAndExpires() throws Exception {
        String verifier = TestSessions.verifier();
        String code = code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice"));
        accessToken(code, verifier);
        exchange(code, verifier).andExpect(status().isBadRequest());

        String late = code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice"));
        db.sql("UPDATE app_sign_in_codes SET expires_at = now() - INTERVAL '1 second'")
                .update();
        exchange(late, verifier)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGN_IN_CODE"));
    }

    @Test
    void concurrentExchangesOfOneCodeIssueExactlyOneSession() throws Exception {
        String verifier = TestSessions.verifier();
        String code = code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice"));
        Callable<Integer> attempt =
                () -> exchange(code, verifier).andReturn().getResponse().getStatus();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(attempt, attempt, attempt, attempt));
            int successes = 0;
            for (var result : results) if (result.get() == 200) successes++;
            assertEquals(1, successes);
        }
        assertEquals(
                1,
                db.sql("SELECT count(*) FROM app_sessions").query(Integer.class).single());
    }

    @Test
    void signingInDeletesExpiredCodesAndSessionsSoTheyDoNotAccumulate() throws Exception {
        String abandoned = TestSessions.verifier();
        code(completeGitHubLogin(TestSessions.challenge(abandoned), 42, "test-only-alice"));
        TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        db.sql("UPDATE app_sign_in_codes SET expires_at = now() - INTERVAL '1 second'")
                .update();
        db.sql("UPDATE app_sessions SET expires_at = now() - INTERVAL '1 second'")
                .update();

        String verifier = TestSessions.verifier();
        accessToken(code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice")), verifier);
        assertEquals(
                0,
                db.sql("SELECT count(*) FROM app_sign_in_codes")
                        .query(Integer.class)
                        .single());
        assertEquals(
                1,
                db.sql("SELECT count(*) FROM app_sessions").query(Integer.class).single());
    }

    @Test
    void aNewSignInCanUseAnotherAccount() throws Exception {
        String verifier = TestSessions.verifier();
        accessToken(code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-alice")), verifier);
        String bob = code(completeGitHubLogin(TestSessions.challenge(verifier), 84, "test-only-bob"));
        exchange(bob, verifier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.githubUserId").value(84));
        exchange(bob, verifier).andExpect(status().isBadRequest());
    }

    @Test
    void malformedExchangeRequestsAreRejected() throws Exception {
        exchange("not-a-code", TestSessions.verifier()).andExpect(status().isBadRequest());
        mvc.perform(post("/api/app-auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void secondSignInKeepsTheSameUserAndUpdatesTheLogin() throws Exception {
        String verifier = TestSessions.verifier();
        accessToken(code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-old")), verifier);
        String token =
                accessToken(code(completeGitHubLogin(TestSessions.challenge(verifier), 42, "test-only-new")), verifier);
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.login").value("test-only-new"));
        assertEquals(
                1, db.sql("SELECT count(*) FROM users").query(Integer.class).single());
    }

    @Test
    void invalidExpiredAndRevokedSessionsAreRejected() throws Exception {
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_GITHUB_TOKEN");
        mvc.perform(get("/api/auth/me")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + alice.replace(alice.charAt(0), alice.charAt(0) == 'A' ? 'B' : 'A')))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Basic " + alice))
                .andExpect(status().isUnauthorized());

        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_GITHUB_TOKEN", withNoContent());
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isOk());

        db.sql("UPDATE app_sessions SET expires_at = now() - INTERVAL '1 second'")
                .update();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionOfAGitHubUserNotOnTheAllowListIsRefused() throws Exception {
        // 99 is not in TestEnvironment's reporead.allowed-github-user-ids, for example a user removed from the list.
        String removed = TestSessions.signIn(sessions, clients, registrations, 99, "TEST_ONLY_REMOVED_GITHUB_TOKEN");
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + removed))
                .andExpect(status().isUnauthorized());
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk());
    }

    @Test
    void signingOutTheLastSessionDeletesTheStoredGitHubToken() throws Exception {
        String phone = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        String tablet = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        String expired = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_GITHUB_TOKEN");
        db.sql(
                        "UPDATE app_sessions SET expires_at = now() - INTERVAL '1 second' WHERE token_hash = sha256(convert_to(:token, 'UTF8'))")
                .param("token", expired)
                .update();

        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + phone))
                .andExpect(status().isNoContent());
        assertNotNull(clients.loadAuthorizedClient("github", "42"), "another device is still signed in");

        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_GITHUB_TOKEN", withNoContent());
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + tablet))
                .andExpect(status().isNoContent());
        assertNull(clients.loadAuthorizedClient("github", "42"), "an expired session does not keep the token");
        assertNotNull(clients.loadAuthorizedClient("github", "84"));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 422, 503})
    void signOutStillDeletesTheTokenWhenGitHubDoesNotRevokeIt(int failure) throws Exception {
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        TestSessions.expectAuthorizationRevoked(
                github, "TEST_ONLY_ALICE_GITHUB_TOKEN", withStatus(HttpStatus.valueOf(failure)));
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNoContent());
        assertNull(clients.loadAuthorizedClient("github", "42"));
    }

    @Test
    void signOutStillDeletesTheTokenWhenGitHubTimesOut() throws Exception {
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        TestSessions.expectAuthorizationRevoked(
                github, "TEST_ONLY_ALICE_GITHUB_TOKEN", withException(new SocketTimeoutException("TEST_ONLY_TIMEOUT")));
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNoContent());
        assertNull(clients.loadAuthorizedClient("github", "42"));
    }

    @Test
    void signOutWithoutAStoredGitHubTokenMakesNoGitHubCall() throws Exception {
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_GITHUB_TOKEN");
        clients.removeAuthorizedClient("github", "42");
        mvc.perform(delete("/api/app-auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNoContent());
    }
}
