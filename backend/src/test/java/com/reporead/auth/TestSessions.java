package com.reporead.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        saveGitHubToken(clients, registrations, githubUserId, githubToken, Instant.now().plusSeconds(600));
        String verifier = verifier();
        long userId = sessions.saveUser(githubUserId, "test-only-user-" + githubUserId);
        String code = sessions.issueSignInCode(userId, challenge(verifier));
        return sessions.exchange(code, verifier).orElseThrow().accessToken();
    }

    public static void saveGitHubToken(OAuth2AuthorizedClientService clients, ClientRegistrationRepository registrations,
                                       long githubUserId, String value, Instant expires) {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
            Map.of("id", githubUserId, "login", "test-only-user-" + githubUserId), "id");
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, Instant.now().minusSeconds(60), expires);
        clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registrations.findByRegistrationId("github"),
            principal.getName(), token), authentication);
    }
}
