package com.reporead.auth;

import com.reporead.RepoReadProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Component;

/**
 * The server's GitHub user tokens: one github_tokens row per GitHub user, so a sign-in survives restarts and deploys.
 * Replaces Spring's in-memory store; Spring's JDBC store would keep the tokens in plaintext. Both tokens are encrypted
 * with AES-256-GCM under the key in reporead.github.token-key-file, and each ciphertext is bound to its user and token
 * kind, so a row copied to another user does not decrypt. A row the current key cannot decrypt (the key file was
 * replaced) reads as no token: that user signs in again, which overwrites it.
 */
@Component
public class GitHubTokens implements OAuth2AuthorizedClientService {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubTokens.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;
    private final ClientRegistrationRepository registrations;
    private final SecretKey key;

    public GitHubTokens(JdbcClient db, ClientRegistrationRepository registrations, RepoReadProperties properties)
            throws IOException {
        this.db = db;
        this.registrations = registrations;
        this.key = readKey(properties.github().tokenKeyFile());
    }

    private static SecretKey readKey(Path file) throws IOException {
        String hex = SecretFiles.read(file);
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IOException(
                    "GitHub token key file must hold 64 lowercase hex characters (openssl rand -hex 32): " + file);
        }
        return new SecretKeySpec(HexFormat.of().parseHex(hex), "AES");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, String principalName) {
        long githubUserId = githubUserId(clientRegistrationId, principalName);
        record Row(
                byte[] accessToken,
                Instant accessTokenIssuedAt,
                Instant accessTokenExpiresAt,
                byte[] refreshToken,
                Instant refreshTokenIssuedAt) {}
        var row =
                db.sql("""
                SELECT access_token, access_token_issued_at, access_token_expires_at, refresh_token, refresh_token_issued_at
                FROM github_tokens WHERE github_user_id = :githubUserId""").param("githubUserId", githubUserId).query(Row.class).optional();
        if (row.isEmpty()) return null;
        OAuth2AccessToken access;
        OAuth2RefreshToken refresh;
        try {
            // GitHub App user tokens carry no scopes.
            access = new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    decrypt(row.get().accessToken(), githubUserId, "access"),
                    row.get().accessTokenIssuedAt(),
                    row.get().accessTokenExpiresAt());
            refresh = row.get().refreshToken() == null
                    ? null
                    : new OAuth2RefreshToken(
                            decrypt(row.get().refreshToken(), githubUserId, "refresh"),
                            row.get().refreshTokenIssuedAt());
        } catch (AEADBadTagException failure) {
            // Kept: a key file named by mistake must not destroy every token; the next sign-in overwrites the row.
            LOG.warn(
                    "Stored GitHub token does not decrypt with the current key; sign-in required; githubUserId={}",
                    githubUserId);
            return null;
        }
        return (T) new OAuth2AuthorizedClient(
                registrations.findByRegistrationId(GitHubBrowserSignIn.REGISTRATION), principalName, access, refresh);
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal) {
        long githubUserId = githubUserId(client.getClientRegistration().getRegistrationId(), principal.getName());
        var access = client.getAccessToken();
        var refresh = client.getRefreshToken();
        if (!OAuth2AccessToken.TokenType.BEARER.equals(access.getTokenType())) {
            throw new IllegalArgumentException("GitHub user token must be a bearer token; got "
                    + access.getTokenType().getValue());
        }
        db.sql("""
                INSERT INTO github_tokens (github_user_id, access_token, access_token_issued_at, access_token_expires_at,
                                           refresh_token, refresh_token_issued_at, updated_at)
                VALUES (:githubUserId, :access, :accessIssued, :accessExpires, :refresh, :refreshIssued, now())
                ON CONFLICT (github_user_id) DO UPDATE SET access_token = excluded.access_token,
                    access_token_issued_at = excluded.access_token_issued_at, access_token_expires_at = excluded.access_token_expires_at,
                    refresh_token = excluded.refresh_token, refresh_token_issued_at = excluded.refresh_token_issued_at,
                    updated_at = excluded.updated_at""")
                .param("githubUserId", githubUserId)
                .param("access", encrypt(access.getTokenValue(), githubUserId, "access"))
                .param("accessIssued", timestamp(access.getIssuedAt()))
                .param("accessExpires", timestamp(access.getExpiresAt()))
                .param("refresh", refresh == null ? null : encrypt(refresh.getTokenValue(), githubUserId, "refresh"))
                .param("refreshIssued", refresh == null ? null : timestamp(refresh.getIssuedAt()))
                .update();
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, String principalName) {
        remove(githubUserId(clientRegistrationId, principalName));
    }

    private void remove(long githubUserId) {
        db.sql("DELETE FROM github_tokens WHERE github_user_id = :githubUserId")
                .param("githubUserId", githubUserId)
                .update();
    }

    /**
     * RepoRead registers only GitHub, whose OAuth principal name is the numeric GitHub user id (userNameAttributeName
     * "id").
     */
    private static long githubUserId(String clientRegistrationId, String principalName) {
        if (!GitHubBrowserSignIn.REGISTRATION.equals(clientRegistrationId)) {
            throw new IllegalArgumentException(
                    "Only the github registration has stored tokens; got " + clientRegistrationId);
        }
        long id = Long.parseLong(principalName);
        if (id <= 0) throw new IllegalArgumentException("GitHub user id must be positive; got " + principalName);
        return id;
    }

    /** The IV, then the ciphertext and tag. The associated data names the user and token kind. */
    private byte[] encrypt(String token, long githubUserId, String kind) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(githubUserId, kind));
            byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] stored = Arrays.copyOf(iv, IV_BYTES + sealed.length);
            System.arraycopy(sealed, 0, stored, IV_BYTES, sealed.length);
            return stored;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("The JDK must provide AES-256-GCM", failure);
        }
    }

    private String decrypt(byte[] stored, long githubUserId, String kind) throws AEADBadTagException {
        if (stored.length < IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("Stored GitHub " + kind + " token is " + stored.length
                    + " bytes, shorter than an IV and tag; githubUserId=" + githubUserId);
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            cipher.updateAAD(associatedData(githubUserId, kind));
            return new String(cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (AEADBadTagException failure) {
            throw failure;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("The JDK must provide AES-256-GCM", failure);
        }
    }

    private static byte[] associatedData(long githubUserId, String kind) {
        return ("reporead-github-token:" + githubUserId + ":" + kind).getBytes(StandardCharsets.UTF_8);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
