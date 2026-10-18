// Owns real security-chain OAuth callback coverage using explicitly test-only upstream responses.

package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

// Loud test-only credentials; GitHub is faked at HTTP, and nothing here reaches it.
/** Owns callback ordering, token persistence and browser/API isolation assertions at the real filter boundary. */
@ExtendWith(OutputCaptureExtension.class)
class GitHubLoginTest extends SharedSpringTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    RestClientAuthorizationCodeTokenResponseClient githubCodeExchange;

    @Autowired
    DefaultOAuth2UserService githubUserInfo;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient db;

    @Autowired
    AppSessions sessions;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    @Qualifier("githubUserApi")
    RestTemplate githubUserApi;

    /** Revocation must not pass a callback between GitHub issuing its token and the server publishing that sign-in. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/app-auth/session", "/api/account"})
    void credentialRemovalWaitsForAnAlreadyAdmittedOAuthCallback(String path) throws Exception {
        TestEnvironment.reset(db);
        String alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_OLD_TOKEN");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tokens = RestClient.builder();
        var tokenEndpoint = MockRestServiceServer.bindTo(tokens).build();
        githubCodeExchange.setRestClient(GitHubSecurity.tokenApi(tokens, meters));
        tokenEndpoint
                .expect(requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(request -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS), "Test must release the callback");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Test callback interrupted", error);
                    }
                    return withSuccess(
                                    "{\"access_token\":\"TEST_ONLY_NEW_TOKEN\",\"expires_in\":28800,\"token_type\":\"bearer\"}",
                                    MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        var users = new RestTemplate();
        users.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        var userEndpoint = MockRestServiceServer.bindTo(users).build();
        githubUserInfo.setRestOperations(users);
        userEndpoint
                .expect(requestTo("https://api.github.com/user"))
                .andRespond(withSuccess("{\"id\":42,\"login\":\"test-only-user\"}", MediaType.APPLICATION_JSON));
        var revoke = MockRestServiceServer.bindTo(githubUserApi).build();
        revoke.expect(requestTo("https://api.github.com/applications/test-only-not-a-github-app/grant"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        var session = appSignIn();
        var authorize =
                URI.create(mvc.perform(get("/oauth2/authorization/github").session(session))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var callback = executor.submit(() -> mvc.perform(get("/login/oauth2/code/github")
                            .session(session)
                            .param("code", "TEST_ONLY_CODE")
                            .param("state", query(authorize).get("state")))
                    .andExpect(status().isFound()));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "Callback must reach token exchange");
                var removed = executor.submit(
                        () -> mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)));
                assertThrows(
                        TimeoutException.class,
                        () -> removed.get(300, TimeUnit.MILLISECONDS),
                        "Credential removal must wait for the admitted callback");
                release.countDown();
                callback.get(10, TimeUnit.SECONDS);
                removed.get(10, TimeUnit.SECONDS).andExpect(status().is(path.equals("/api/account") ? 200 : 204));
                assertNull(clients.loadAuthorizedClient("github", "42"));
                assertEquals(0, count("app_sign_in_codes"));
            } finally {
                release.countDown();
            }
        }
        tokenEndpoint.verify();
        userEndpoint.verify();
        revoke.verify();
    }

    /** A browser session that the app's /app/sign-in began, holding its PKCE challenge. */
    private MockHttpSession appSignIn() throws Exception {
        return (MockHttpSession) mvc.perform(
                        get("/app/sign-in").param("code_challenge", TestSessions.challenge(TestSessions.verifier())))
                .andExpect(status().isFound())
                .andReturn()
                .getRequest()
                .getSession();
    }

    @Test
    void loginRedirectHasExactCallbackStateAndPkceButNoBroadScopes() throws Exception {
        MvcResult first = mvc.perform(
                        get("/oauth2/authorization/github").session(appSignIn()).header("Host", "untrusted.example"))
                .andExpect(status().isFound())
                .andReturn();
        URI redirect = URI.create(first.getResponse().getHeader("Location"));
        assertEquals("https", redirect.getScheme());
        assertEquals("github.com", redirect.getHost());
        assertEquals("/login/oauth/authorize", redirect.getPath());
        Map<String, String> params = query(redirect);
        assertEquals("test-only-not-a-github-app", params.get("client_id"));
        assertEquals("http://127.0.0.1:8081/login/oauth2/code/github", params.get("redirect_uri"));
        assertEquals("S256", params.get("code_challenge_method"));
        assertFalse(params.get("state").isBlank());
        assertFalse(params.get("code_challenge").isBlank());
        assertFalse(params.containsKey("scope"));
        assertFalse(params.containsKey("client_secret"));
        assertFalse(params.containsKey("code_verifier"));
        URI second = URI.create(mvc.perform(get("/oauth2/authorization/github").session(appSignIn()))
                .andReturn()
                .getResponse()
                .getHeader("Location"));
        assertNotEquals(params.get("state"), query(second).get("state"));
    }

    /** Only /app/sign-in starts a GitHub login, so a login without the app's challenge never stores a GitHub token. */
    @Test
    void gitHubLoginNotStartedByTheAppIsRefusedBeforeReachingGitHub() throws Exception {
        mvc.perform(get("/oauth2/authorization/github"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/oauth2/authorization/github").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    /** Only /app/sign-in keeps anything for a browser that has not signed in; a refused request opens no session. */
    @Test
    void refusedBrowserRequestsOpenNoSession() throws Exception {
        for (String path : List.of("/", "/oauth2/authorization/github", "/test-only/unknown")) {
            var refused =
                    mvc.perform(get(path)).andExpect(status().isUnauthorized()).andReturn();
            assertNull(refused.getRequest().getSession(false), path);
            // A refused POST too: a CSRF token stored for it would open a session, against the sign-in session cap.
            var posted = mvc.perform(post(path)).andReturn();
            assertNull(
                    posted.getRequest().getSession(false),
                    "POST " + path + " " + posted.getResponse().getStatus());
        }
    }

    @Test
    void browserOAuthSessionCannotAuthenticateTheApi() throws Exception {
        var user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OAUTH2_USER")), Map.of("id", 42L, "login", "test-only-user"), "id");
        var session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(user, user.getAuthorities(), "github")));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCallbackHasAnExplicitFailureAndDoesNotAuthenticate(CapturedOutput log) throws Exception {
        mvc.perform(get("/login/oauth2/code/github")
                        .param("code", "test-only-invalid-code")
                        .param("state", "not-issued"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/auth/failed"));
        // The log names the step that failed: here, no sign-in issued that state.
        assertTrue(
                log.getOut()
                        .contains(
                                "GitHub sign-in rejected; failureType=OAuth2AuthenticationException error=authorization_request_not_found"));
        mvc.perform(get("/auth/failed"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign-in failed")));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    /**
     * The whole browser half through the real filter chain: the code exchange, GitHub's user, then the allow-list
     * (ADR-16), which admits 42 and 84 here.
     */
    @Test
    void anAllowedGitHubUserSignsInAndTheirTokenIsStored() throws Exception {
        TestEnvironment.reset(db);
        String redirect = signInAsGitHubUser(42)
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertTrue(redirect.startsWith("reporead-dev://auth?code="), redirect);
        assertEquals(1, count("github_tokens where github_user_id = 42"));
        assertEquals(1, count("users where github_user_id = 42"));
        assertEquals(1, count("app_sign_in_codes"));
    }

    /** The allow-list refuses while GitHub's answer is still being checked, before Spring stores the token. */
    @Test
    void aGitHubUserOffTheAllowListIsRefusedBeforeAnythingIsStored() throws Exception {
        TestEnvironment.reset(db);
        signInAsGitHubUser(99).andExpect(status().isFound()).andExpect(redirectedUrl("/auth/failed"));
        assertEquals(0, count("github_tokens"));
        assertEquals(0, count("users"));
        assertEquals(0, count("app_sign_in_codes"));
    }

    @Test
    void aCodeGitHubRefusesIsLoggedWithGitHubsReason(CapturedOutput log) throws Exception {
        TestEnvironment.reset(db);
        var tokens = RestClient.builder();
        var tokenEndpoint = MockRestServiceServer.bindTo(tokens).build();
        githubCodeExchange.setRestClient(GitHubSecurity.tokenApi(tokens, meters));
        // GitHub refuses a code with HTTP 200 and an error body.
        tokenEndpoint
                .expect(requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("{\"error\":\"bad_verification_code\"}", MediaType.APPLICATION_JSON));
        var session = appSignIn();
        URI authorize =
                URI.create(mvc.perform(get("/oauth2/authorization/github").session(session))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location"));
        mvc.perform(get("/login/oauth2/code/github")
                        .session(session)
                        .param("code", "TEST_ONLY_CODE")
                        .param("state", query(authorize).get("state")))
                .andExpect(redirectedUrl("/auth/failed"));
        tokenEndpoint.verify();
        assertTrue(
                log.getOut()
                        .contains(
                                "GitHub sign-in rejected; failureType=OAuth2AuthenticationException error=bad_verification_code"));
        assertEquals(0, count("github_tokens"));
    }

    /** Starts the app's sign-in and returns GitHub's callback for {@code githubUserId}, GitHub answering from fakes. */
    private ResultActions signInAsGitHubUser(long githubUserId) throws Exception {
        var tokens = RestClient.builder();
        var tokenEndpoint = MockRestServiceServer.bindTo(tokens).build();
        githubCodeExchange.setRestClient(GitHubSecurity.tokenApi(tokens, meters));
        tokenEndpoint
                .expect(requestTo("https://github.com/login/oauth/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"TEST_ONLY_TOKEN\",\"expires_in\":28800,"
                                + "\"refresh_token\":\"TEST_ONLY_REFRESH\",\"refresh_token_expires_in\":15724800,"
                                + "\"token_type\":\"bearer\",\"scope\":\"\"}",
                        MediaType.APPLICATION_JSON));
        var users = new RestTemplate();
        users.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        var userEndpoint = MockRestServiceServer.bindTo(users).build();
        githubUserInfo.setRestOperations(users);
        userEndpoint
                .expect(requestTo("https://api.github.com/user"))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer TEST_ONLY_TOKEN"))
                .andRespond(withSuccess(
                        "{\"id\":" + githubUserId + ",\"login\":\"test-only-user\"}", MediaType.APPLICATION_JSON));
        var session = appSignIn();
        URI authorize =
                URI.create(mvc.perform(get("/oauth2/authorization/github").session(session))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location"));
        var callback = mvc.perform(get("/login/oauth2/code/github")
                .session(session)
                .param("code", "TEST_ONLY_CODE")
                .param("state", query(authorize).get("state")));
        tokenEndpoint.verify();
        userEndpoint.verify();
        return callback;
    }

    private int count(String tableAndCondition) {
        return db.sql("select count(*) from " + tableAndCondition)
                .query(Integer.class)
                .single();
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
