package com.reporead.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    // Loud test-only credentials; no GitHub requests or successful OAuth exchange are claimed here.
    "reporead.github.client-id=test-only-not-a-github-app"
})
@AutoConfigureMockMvc
class GitHubLoginTest {
    @DynamicPropertySource
    static void testSecret(DynamicPropertyRegistry properties) {
        try {
            var file = java.nio.file.Files.createTempFile("reporead-test-client-secret-", ".txt");
            java.nio.file.Files.writeString(file, "TEST_ONLY_NOT_A_REAL_SECRET\n");
            java.nio.file.Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            file.toFile().deleteOnExit();
            properties.add("reporead.github.client-secret-file", file::toString);
        } catch (java.io.IOException error) {
            throw new ExceptionInInitializerError(error);
        }
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

    @Test void apiRequiresSignInWithoutStartingAnExternalCall() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void repositoryAccessRequiresAServerSideGitHubUserToken() throws Exception {
        mvc.perform(get("/api/auth/installations/1/repositories").with(oauth2Login()))
            .andExpect(status().isUnauthorized());
    }

    @Test void invalidCallbackHasAnExplicitFailureAndDoesNotAuthenticate() throws Exception {
        mvc.perform(get("/login/oauth2/code/github").param("code", "test-only-invalid-code").param("state", "not-issued"))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/auth/failed"));
        mvc.perform(get("/auth/failed")).andExpect(status().isUnauthorized())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign-in failed")));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void identityResponseContainsNoTokenOrSecret() throws Exception {
        mvc.perform(get("/api/auth/me").with(oauth2Login().attributes(attrs -> {
            attrs.put("id", 42L); attrs.put("login", "test-only-user");
        }))).andExpect(status().isOk())
            .andExpect(content().json("{\"id\":42,\"login\":\"test-only-user\"}", JsonCompareMode.STRICT));
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(pair -> pair.split("=", 2))
            .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
