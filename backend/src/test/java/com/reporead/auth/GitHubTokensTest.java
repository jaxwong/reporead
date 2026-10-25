package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.reporead.RepoReadProperties;
import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;

/** Stored GitHub tokens. Every token and key here is test-only; no GitHub call. */
class GitHubTokensTest extends SharedSpringTest {
    @Autowired
    RepoReadProperties properties;

    Instant expires;

    @BeforeEach
    void setup() {
        TestEnvironment.reset(db);
        // PostgreSQL keeps microseconds.
        expires = Instant.now().plusSeconds(28800).truncatedTo(ChronoUnit.MICROS);
        TestSessions.saveGitHubToken(
                clients, registrations, 42, "TEST_ONLY_ACCESS_TOKEN", expires, "TEST_ONLY_REFRESH_TOKEN");
    }

    private GitHubTokens restartedWithKey(String key) throws IOException {
        var keyFile = TestEnvironment.secretFile("reporead-test-token-key-", key);
        var github = new RepoReadProperties.GitHub(
                properties.github().clientId(), properties.github().clientSecretFile(), keyFile);
        return new GitHubTokens(
                db,
                registrations,
                new RepoReadProperties(properties.publicUrl(), properties.allowedGithubUserIds(), github));
    }

    private int rows() {
        return db.sql("SELECT count(*) FROM github_tokens").query(Integer.class).single();
    }

    @Test
    void tokensAreStoredEncryptedAndSurviveARestart() throws IOException {
        byte[] stored = db.sql(
                        "SELECT access_token || coalesce(refresh_token, ''::bytea) FROM github_tokens WHERE github_user_id = 42")
                .query(byte[].class)
                .single();
        String raw = new String(stored, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("TEST_ONLY"), "no token is stored in plaintext");

        OAuth2AuthorizedClient loaded =
                restartedWithKey(TestEnvironment.TOKEN_KEY).loadAuthorizedClient("github", "42");
        assertEquals("TEST_ONLY_ACCESS_TOKEN", loaded.getAccessToken().getTokenValue());
        assertEquals(expires, loaded.getAccessToken().getExpiresAt());
        assertEquals("TEST_ONLY_REFRESH_TOKEN", loaded.getRefreshToken().getTokenValue());
        assertEquals("42", loaded.getPrincipalName());
        assertEquals("github", loaded.getClientRegistration().getRegistrationId());
    }

    @Test
    void savingAgainReplacesBothTokens() {
        TestSessions.saveGitHubToken(
                clients, registrations, 42, "TEST_ONLY_ACCESS_TOKEN_2", expires, "TEST_ONLY_REFRESH_TOKEN_2");
        OAuth2AuthorizedClient loaded = clients.loadAuthorizedClient("github", "42");
        assertEquals("TEST_ONLY_ACCESS_TOKEN_2", loaded.getAccessToken().getTokenValue());
        assertEquals("TEST_ONLY_REFRESH_TOKEN_2", loaded.getRefreshToken().getTokenValue());
        assertEquals(1, rows());
    }

    @Test
    void aTokenWithoutRefreshTokenLoadsWithout() {
        TestSessions.saveGitHubToken(clients, registrations, 42, "TEST_ONLY_ACCESS_TOKEN", expires, null);
        OAuth2AuthorizedClient loaded = clients.loadAuthorizedClient("github", "42");
        assertEquals("TEST_ONLY_ACCESS_TOKEN", loaded.getAccessToken().getTokenValue());
        assertNull(loaded.getRefreshToken());
    }

    @Test
    void aWrongKeyReadsNoTokenAndKeepsItForTheRightKey() throws IOException {
        var otherKey = restartedWithKey("0f".repeat(32));
        assertNull(otherKey.loadAuthorizedClient("github", "42"));
        assertEquals(1, rows(), "a key file named by mistake must not destroy the stored token");
        OAuth2AuthorizedClient restored =
                restartedWithKey(TestEnvironment.TOKEN_KEY).loadAuthorizedClient("github", "42");
        assertEquals("TEST_ONLY_ACCESS_TOKEN", restored.getAccessToken().getTokenValue());
    }

    @Test
    void aRowMovedToAnotherUserDoesNotDecrypt() {
        db.sql("UPDATE github_tokens SET github_user_id = 84 WHERE github_user_id = 42")
                .update();
        assertNull(clients.loadAuthorizedClient("github", "84"));
    }

    @Test
    void removeDeletesTheRow() {
        clients.removeAuthorizedClient("github", "42");
        assertEquals(0, rows());
        assertNull(clients.loadAuthorizedClient("github", "42"));
    }

    @Test
    void onlyTheGitHubRegistrationIsStored() {
        assertThrows(IllegalArgumentException.class, () -> clients.loadAuthorizedClient("other", "42"));
        assertThrows(IllegalArgumentException.class, () -> clients.loadAuthorizedClient("github", "0"));
    }

    /** Each of these is a valid secret file (SecretFiles), so only the key's own format check refuses it. */
    @Test
    void theKeyFileMustHoldExactly32HexBytes() {
        for (String bad : new String[] {"7e57".repeat(15), "7E57".repeat(16), "zz".repeat(32), "7e57".repeat(17)}) {
            var refused = assertThrows(IOException.class, () -> restartedWithKey(bad), bad);
            assertTrue(refused.getMessage().startsWith("GitHub token key file must hold 64 lowercase hex"), bad);
        }
    }
}
