package com.reporead.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Owns RepoRead users, single-use sign-in codes, and Android bearer sessions.
 * Codes and session tokens are random 256-bit values; only their SHA-256 hashes are stored.
 */
@Component
public class AppSessions {
    static final Duration SIGN_IN_CODE_LIFETIME = Duration.ofSeconds(60);
    static final Duration SESSION_LIFETIME = Duration.ofDays(30);
    /** base64url without padding of 32 bytes; also the length of an S256 PKCE challenge. */
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;

    public AppSessions(JdbcClient db) {
        this.db = db;
    }

    public record IssuedSession(String accessToken, Instant expiresAt, AppUser user) {}

    long saveUser(long githubUserId, String login) {
        return db.sql("""
                insert into users (github_user_id, login) values (:githubUserId, :login)
                on conflict (github_user_id) do update set login = excluded.login
                returning id""")
            .param("githubUserId", githubUserId).param("login", login).query(Long.class).single();
    }

    String issueSignInCode(long userId, String codeChallenge) {
        if (!TOKEN.matcher(codeChallenge).matches()) throw new IllegalArgumentException("codeChallenge must be an S256 PKCE challenge");
        String code = randomToken();
        db.sql("insert into app_sign_in_codes (code_hash, user_id, code_challenge, expires_at) values (:hash, :userId, :challenge, :expiresAt)")
            .param("hash", hash(code)).param("userId", userId).param("challenge", codeChallenge)
            .param("expiresAt", Timestamp.from(Instant.now().plus(SIGN_IN_CODE_LIFETIME))).update();
        return code;
    }

    /** Consumes the code whether or not the exchange succeeds, so a code can never be guessed against twice. */
    Optional<IssuedSession> exchange(String code, String codeVerifier) {
        if (code == null || codeVerifier == null || !TOKEN.matcher(code).matches() || !VERIFIER.matcher(codeVerifier).matches()) {
            return Optional.empty();
        }
        record Pending(long userId, String challenge, Instant expiresAt) {}
        var pending = db.sql("delete from app_sign_in_codes where code_hash = :hash returning user_id, code_challenge, expires_at")
            .param("hash", hash(code))
            .query((row, n) -> new Pending(row.getLong(1), row.getString(2), row.getTimestamp(3).toInstant())).optional();
        if (pending.isEmpty() || !pending.get().expiresAt().isAfter(Instant.now())) return Optional.empty();
        byte[] expected = pending.get().challenge().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(codeVerifier)).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();

        String token = randomToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(SESSION_LIFETIME);
        db.sql("insert into app_sessions (token_hash, user_id, created_at, expires_at) values (:hash, :userId, :createdAt, :expiresAt)")
            .param("hash", hash(token)).param("userId", pending.get().userId())
            .param("createdAt", Timestamp.from(now)).param("expiresAt", Timestamp.from(expiresAt)).update();
        var user = db.sql("select id, github_user_id, login from users where id = :id").param("id", pending.get().userId())
            .query((row, n) -> new AppUser(row.getLong(1), row.getLong(2), row.getString(3))).single();
        return Optional.of(new IssuedSession(token, expiresAt, user));
    }

    Optional<AppUser> authenticate(String token) {
        if (!TOKEN.matcher(token).matches()) return Optional.empty();
        return db.sql("""
                select u.id, u.github_user_id, u.login from app_sessions s join users u on u.id = s.user_id
                where s.token_hash = :hash and s.expires_at > :now""")
            .param("hash", hash(token)).param("now", Timestamp.from(Instant.now()))
            .query((row, n) -> new AppUser(row.getLong(1), row.getLong(2), row.getString(3))).optional();
    }

    /** Deletes the user, their sign-in codes, and every session; their other data must already be deleted. */
    void deleteUser(long userId) {
        db.sql("delete from app_sign_in_codes where user_id = :userId").param("userId", userId).update();
        db.sql("delete from app_sessions where user_id = :userId").param("userId", userId).update();
        db.sql("delete from users where id = :userId").param("userId", userId).update();
    }

    void revoke(String token) {
        if (TOKEN.matcher(token).matches()) db.sql("delete from app_sessions where token_hash = :hash").param("hash", hash(token)).update();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hash(String token) {
        return sha256(token);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("AppSessions requires the JDK SHA-256 implementation", error);
        }
    }
}
