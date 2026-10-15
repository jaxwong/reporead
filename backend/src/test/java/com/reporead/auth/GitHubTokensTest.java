package com.reporead.auth;

import com.reporead.RepoReadProperties;
import com.reporead.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Stored GitHub tokens. Every token and key here is test-only; no GitHub call. */
@SpringBootTest
class GitHubTokensTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }

    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired JdbcClient db;
    @Autowired RepoReadProperties properties;
    Instant expires;

    @BeforeEach void setup() {
        TestEnvironment.reset(db);
        // PostgreSQL keeps microseconds.
        expires = Instant.now().plusSeconds(28800).truncatedTo(ChronoUnit.MICROS);
        TestSessions.saveGitHubToken(clients, registrations, 42, "TEST_ONLY_ACCESS_TOKEN", expires, "TEST_ONLY_REFRESH_TOKEN");
    }

    private GitHubTokens restartedWithKey(String key) throws IOException {
        var keyFile = TestEnvironment.secretFile("reporead-test-token-key-", key);
        var github = new RepoReadProperties.GitHub(properties.github().clientId(), properties.github().clientSecretFile(), keyFile);
        return new GitHubTokens(db, registrations, new RepoReadProperties(properties.publicUrl(), properties.allowedGithubUserIds(), github));
    }

    private int rows() {
        return db.sql("select count(*) from github_tokens").query(Integer.class).single();
    }

    @Test void tokensAreStoredEncryptedAndSurviveARestart() throws IOException {
        byte[] stored = db.sql("select access_token || coalesce(refresh_token, ''::bytea) from github_tokens where github_user_id = 42")
            .query(byte[].class).single();
        String raw = new String(stored, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("TEST_ONLY"), "no token is stored in plaintext");

        OAuth2AuthorizedClient loaded = restartedWithKey(TestEnvironment.TOKEN_KEY).loadAuthorizedClient("github", "42");
        assertEquals("TEST_ONLY_ACCESS_TOKEN", loaded.getAccessToken().getTokenValue());
        assertEquals(expires, loaded.getAccessToken().getExpiresAt());
        assertEquals("TEST_ONLY_REFRESH_TOKEN", loaded.getRefreshToken().getTokenValue());
        assertEquals("42", loaded.getPrincipalName());
        assertEquals("github", loaded.getClientRegistration().getRegistrationId());
    }

    @Test void savingAgainReplacesBothTokens() {
        TestSessions.saveGitHubToken(clients, registrations, 42, "TEST_ONLY_ACCESS_TOKEN_2", expires, "TEST_ONLY_REFRESH_TOKEN_2");
        OAuth2AuthorizedClient loaded = clients.loadAuthorizedClient("github", "42");
        assertEquals("TEST_ONLY_ACCESS_TOKEN_2", loaded.getAccessToken().getTokenValue());
        assertEquals("TEST_ONLY_REFRESH_TOKEN_2", loaded.getRefreshToken().getTokenValue());
        assertEquals(1, rows());
    }

    @Test void aTokenWithoutRefreshTokenLoadsWithout() {
        TestSessions.saveGitHubToken(clients, registrations, 42, "TEST_ONLY_ACCESS_TOKEN", expires, null);
        OAuth2AuthorizedClient loaded = clients.loadAuthorizedClient("github", "42");
        assertEquals("TEST_ONLY_ACCESS_TOKEN", loaded.getAccessToken().getTokenValue());
        assertNull(loaded.getRefreshToken());
    }

    @Test void aReplacedKeyDropsTheStoredTokenSoTheUserSignsInAgain() throws IOException {
        var otherKey = restartedWithKey("0f".repeat(32));
        assertNull(otherKey.loadAuthorizedClient("github", "42"));
        assertEquals(0, rows(), "an unreadable token is deleted, not kept");
        assertNull(clients.loadAuthorizedClient("github", "42"));
    }

    @Test void aRowMovedToAnotherUserDoesNotDecrypt() {
        db.sql("update github_tokens set github_user_id = 84 where github_user_id = 42").update();
        assertNull(clients.loadAuthorizedClient("github", "84"));
    }

    @Test void removeDeletesTheRow() {
        clients.removeAuthorizedClient("github", "42");
        assertEquals(0, rows());
        assertNull(clients.loadAuthorizedClient("github", "42"));
    }

    @Test void onlyTheGitHubRegistrationIsStored() {
        assertThrows(IllegalArgumentException.class, () -> clients.loadAuthorizedClient("other", "42"));
        assertThrows(IllegalArgumentException.class, () -> clients.loadAuthorizedClient("github", "0"));
    }

    @Test void theKeyFileMustHoldExactly32HexBytes() throws IOException {
        for (String bad : new String[] {"7e57".repeat(15), "7E57".repeat(16), "zz".repeat(32), "7e57".repeat(17)}) {
            var file = TestEnvironment.secretFile("reporead-test-token-key-", bad);
            assertThrows(IOException.class, () -> GitHubTokens.readKey(file), bad);
        }
    }
}
