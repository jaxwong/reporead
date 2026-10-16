package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.SharedSpringTest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// Loud test-only credentials; no GitHub requests or successful OAuth exchange are claimed here.
class GitHubLoginTest extends SharedSpringTest {
    @Autowired
    MockMvc mvc;

    /** A browser session that the app's /app/sign-in began, holding its PKCE challenge. */
    private MockHttpSession appSignIn() throws Exception {
        return (MockHttpSession) mvc.perform(get("/app/sign-in")
                        .param("code_challenge", TestSessions.challenge(TestSessions.verifier()))
                        .param("scheme", "reporead"))
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
                        .param("scheme", "reporead"))
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
