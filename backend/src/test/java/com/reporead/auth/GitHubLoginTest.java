package com.reporead.auth;

import com.reporead.TestEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Loud test-only credentials; no GitHub requests or successful OAuth exchange are claimed here.
@SpringBootTest
@AutoConfigureMockMvc
class GitHubLoginTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }

    @Autowired MockMvc mvc;

    @Test void loginRedirectHasExactCallbackStateAndPkceButNoBroadScopes() throws Exception {
        MvcResult first = mvc.perform(get("/oauth2/authorization/github").header("Host", "untrusted.example"))
            .andExpect(status().isFound()).andReturn();
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
        URI second = URI.create(mvc.perform(get("/oauth2/authorization/github"))
            .andReturn().getResponse().getHeader("Location"));
        assertNotEquals(params.get("state"), query(second).get("state"));
    }

    @Test void apiRequiresAnAppSessionWithoutStartingAnExternalCall() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/repositories")).andExpect(status().isUnauthorized());
    }

    @Test void browserOAuthSessionCannotAuthenticateTheApi() throws Exception {
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), Map.of("id", 42L, "login", "test-only-user"), "id");
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(new OAuth2AuthenticationToken(user, user.getAuthorities(), "github")));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void invalidCallbackHasAnExplicitFailureAndDoesNotAuthenticate() throws Exception {
        mvc.perform(get("/login/oauth2/code/github").param("code", "test-only-invalid-code").param("state", "not-issued"))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/auth/failed"));
        mvc.perform(get("/auth/failed")).andExpect(status().isUnauthorized())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign-in failed")));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void unknownBrowserPathsAreNotServed() throws Exception {
        mvc.perform(get("/")).andExpect(status().isUnauthorized());
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(pair -> pair.split("=", 2))
            .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
