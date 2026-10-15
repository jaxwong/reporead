package com.reporead.auth;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.Hashes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Owns RepoRead users, single-use sign-in codes, and Android bearer sessions. Codes and session tokens are random
 * 256-bit values; only their SHA-256 hashes are stored.
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
    private final AllowedUsers allowed;

    AppSessions(JdbcClient db, AllowedUsers allowed) {
        this.db = db;
        this.allowed = allowed;
    }

    public record IssuedSession(String accessToken, Instant expiresAt, AppUser user) {}

    long saveUser(long githubUserId, String login) {
        return db.sql("""
                insert into users (github_user_id, login) values (:githubUserId, :login)
                on conflict (github_user_id) do update set login = excluded.login
                returning id""")
                .param("githubUserId", githubUserId)
                .param("login", login)
                .query(Long.class)
                .single();
    }

    /** Also deletes expired codes (abandoned sign-ins), so they cannot accumulate. */
    String issueSignInCode(long userId, String codeChallenge) {
        if (!TOKEN.matcher(codeChallenge).matches())
            throw new IllegalArgumentException("codeChallenge must be an S256 PKCE challenge");
        String code = randomToken();
        Instant now = Instant.now();
        db.sql("delete from app_sign_in_codes where expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
        db.sql(
                        "insert into app_sign_in_codes (code_hash, user_id, code_challenge, expires_at) values (:hash, :userId, :challenge, :expiresAt)")
                .param("hash", hash(code))
                .param("userId", userId)
                .param("challenge", codeChallenge)
                .param("expiresAt", Timestamp.from(now.plus(SIGN_IN_CODE_LIFETIME)))
                .update();
        return code;
    }

    /**
     * Consumes the code whether or not the exchange succeeds, so a code can never be guessed against twice. Issuing a
     * session also deletes expired ones, so they cannot accumulate. {@code expectedUserId} is the account whose data
     * the app already holds, if any: a code for another account issues no session, so a sign-in never puts two
     * accounts' data on one phone, and switching accounts takes the app's sign-out, which says first what it would
     * lose.
     */
    Optional<IssuedSession> exchange(String code, String codeVerifier, Long expectedUserId) {
        if (code == null
                || codeVerifier == null
                || !TOKEN.matcher(code).matches()
                || !VERIFIER.matcher(codeVerifier).matches()) {
            return Optional.empty();
        }
        record Pending(long userId, String challenge, Instant expiresAt) {}
        var pending = db.sql(
                        "delete from app_sign_in_codes where code_hash = :hash returning user_id, code_challenge, expires_at")
                .param("hash", hash(code))
                .query((row, n) -> new Pending(
                        row.getLong("user_id"),
                        row.getString("code_challenge"),
                        row.getTimestamp("expires_at").toInstant()))
                .optional();
        if (pending.isEmpty() || !pending.get().expiresAt().isAfter(Instant.now())) return Optional.empty();
        byte[] expected = pending.get().challenge().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(hash(codeVerifier))
                .getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();
        if (expectedUserId != null && expectedUserId != pending.get().userId()) {
            throw new ApiFailure(
                    Code.ACCOUNT_MISMATCH,
                    "This phone holds another account's notes, so it signs in only as that account. Sign in with the"
                            + " GitHub account that saved them, or sign out first to remove them from this phone.");
        }

        String token = randomToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(SESSION_LIFETIME);
        db.sql("delete from app_sessions where expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
        db.sql(
                        "insert into app_sessions (token_hash, user_id, created_at, expires_at) values (:hash, :userId, :createdAt, :expiresAt)")
                .param("hash", hash(token))
                .param("userId", pending.get().userId())
                .param("createdAt", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
        var user = db.sql("select id, github_user_id, login from users where id = :id")
                .param("id", pending.get().userId())
                .query((row, n) ->
                        new AppUser(row.getLong("id"), row.getLong("github_user_id"), row.getString("login")))
                .single();
        return Optional.of(new IssuedSession(token, expiresAt, user));
    }

    /** The session's user, while it is unexpired and its GitHub user is still on the allow-list. */
    Optional<AppUser> authenticate(String token) {
        if (!TOKEN.matcher(token).matches()) return Optional.empty();
        return db.sql("""
                select u.id, u.github_user_id, u.login from app_sessions s join users u on u.id = s.user_id
                where s.token_hash = :hash and s.expires_at > :now""")
                .param("hash", hash(token))
                .param("now", Timestamp.from(Instant.now()))
                .query((row, n) ->
                        new AppUser(row.getLong("id"), row.getLong("github_user_id"), row.getString("login")))
                .optional()
                .filter(user -> allowed.allows(user.githubUserId()));
    }

    /** Locks the user's row for account deletion; must run inside that transaction, before listing what to delete. */
    public void lockUserForDeletion(long userId) {
        db.sql("select id from users where id = :userId for update")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /**
     * Holds off account deletion while a row that only the user owns commits (a new repository connection); must run
     * inside that write's transaction, before it writes. False when the account is gone, including deleted by a
     * deletion this call waited for, so the write never reaches a foreign-key failure.
     */
    public boolean lockUserForWrite(long userId) {
        return db.sql("select id from users where id = :userId for share")
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    /** Deletes the user, their sign-in codes, and every session; their other data must already be deleted. */
    public void deleteUser(long userId) {
        db.sql("delete from app_sign_in_codes where user_id = :userId")
                .param("userId", userId)
                .update();
        db.sql("delete from app_sessions where user_id = :userId")
                .param("userId", userId)
                .update();
        db.sql("delete from users where id = :userId").param("userId", userId).update();
    }

    /** Deletes this session; returns how many unexpired sessions the user still has on other devices. */
    int revoke(String token, long userId) {
        if (TOKEN.matcher(token).matches())
            db.sql("delete from app_sessions where token_hash = :hash")
                    .param("hash", hash(token))
                    .update();
        return db.sql("select count(*) from app_sessions where user_id = :userId and expires_at > :now")
                .param("userId", userId)
                .param("now", Timestamp.from(Instant.now()))
                .query(Integer.class)
                .single();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of a token, code or PKCE verifier, all of which are ASCII. */
    private static byte[] hash(String value) {
        return Hashes.sha256(value.getBytes(StandardCharsets.US_ASCII));
    }
}
