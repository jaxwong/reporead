package com.reporead;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The hashes RepoRead computes and the one form of Git object id it accepts: SHA-256 for sign-in tokens, mutation
 * fingerprints and block identity, and Git's SHA-1 blob id, which proves GitHub's bytes are the version asked for.
 */
public final class Hashes {
    private static final Pattern GIT_SHA = Pattern.compile("[0-9a-f]{40}");

    private Hashes() {}

    /** A lowercase 40-character Git object id, as GitHub and the app write them; false for null. */
    public static boolean isGitSha(String value) {
        return value != null && GIT_SHA.matcher(value).matches();
    }

    public static byte[] sha256(byte[] bytes) {
        return digest("SHA-256").digest(bytes);
    }

    /** Git's id for a blob of {@code bytes}: the SHA-1 of "blob <length>\0" followed by the bytes. */
    public static String gitBlobSha(byte[] bytes) {
        var sha1 = digest("SHA-1");
        sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(sha1.digest(bytes));
    }

    /** Every Java platform must provide SHA-1 and SHA-256 (MessageDigest's documentation). */
    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("The JDK must provide " + algorithm, failure);
        }
    }
}
