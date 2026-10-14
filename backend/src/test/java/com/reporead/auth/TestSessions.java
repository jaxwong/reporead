package com.reporead.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/** Test-only sign-in through the real AppSessions code exchange, with a fake GitHub user token. Never a live fallback. */
public final class TestSessions {
    private TestSessions() {}

    public static String verifier() {
        return (UUID.randomUUID() + "-" + UUID.randomUUID()).replace("-", "a");
    }

    public static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError("JDK must provide SHA-256", error);
        }
    }

    /** Returns an app bearer token for this GitHub user, and stores githubToken as their server-side GitHub token. */
    public static String signIn(AppSessions sessions, OAuth2AuthorizedClientService clients, ClientRegistrationRepository registrations,
                                long githubUserId, String githubToken) {
        saveGitHubToken(clients, registrations, githubUserId, githubToken, Instant.now().plusSeconds(600), null);
        String verifier = verifier();
        long userId = sessions.saveUser(githubUserId, "test-only-user-" + githubUserId);
        String code = sessions.issueSignInCode(userId, challenge(verifier));
        return sessions.exchange(code, verifier).orElseThrow().accessToken();
    }

    /** Stores a GitHub token as the OAuth callback would; refreshToken may be null, as for a token GitHub never refreshes. */
    /** Expects RepoRead to revoke its GitHub authorization with this user token, authenticated as the test-only App. */
    public static void expectAuthorizationRevoked(MockRestServiceServer github, String userToken, ResponseCreator response) {
        String app = "test-only-not-a-github-app:TEST_ONLY_NOT_A_REAL_SECRET";
        github.expect(requestTo("https://api.github.com/applications/test-only-not-a-github-app/grant"))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(app.getBytes(StandardCharsets.UTF_8))))
            .andExpect(content().json("{\"access_token\":\"" + userToken + "\"}", JsonCompareMode.STRICT))
            .andRespond(response);
    }

    public static void saveGitHubToken(OAuth2AuthorizedClientService clients, ClientRegistrationRepository registrations,
                                       long githubUserId, String value, Instant expires, String refreshToken) {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
            Map.of("id", githubUserId, "login", "test-only-user-" + githubUserId), "id");
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, Instant.now().minusSeconds(60), expires);
        clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registrations.findByRegistrationId("github"),
            principal.getName(), token, refreshToken == null ? null : new OAuth2RefreshToken(refreshToken, Instant.now().minusSeconds(60))),
            authentication);
    }
}
