package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
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

    /** A browser session that the app's /app/sign-in began, holding its PKCE challenge. */
    private MockHttpSession appSignIn() throws Exception {
        return (MockHttpSession) mvc.perform(get("/app/sign-in")
                        .param("code_challenge", TestSessions.challenge(TestSessions.verifier()))
                        .param("scheme", "reporead-dev"))
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
        }
        var malformed = mvc.perform(get("/app/sign-in")
                        .param("code_challenge", "test-only-short")
                        .param("scheme", "reporead-dev"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertNull(malformed.getRequest().getSession(false));
    }

    @Test
    void apiRequiresAnAppSessionWithoutStartingAnExternalCall() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/repositories")).andExpect(status().isUnauthorized());
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
    void invalidCallbackHasAnExplicitFailureAndDoesNotAuthenticate() throws Exception {
        mvc.perform(get("/login/oauth2/code/github")
                        .param("code", "test-only-invalid-code")
                        .param("state", "not-issued"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/auth/failed"));
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

    @Test
    void unknownBrowserPathsAreNotServed() throws Exception {
        mvc.perform(get("/")).andExpect(status().isUnauthorized());
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
