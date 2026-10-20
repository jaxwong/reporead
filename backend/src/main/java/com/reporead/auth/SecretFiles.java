package com.reporead.auth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * The server's secrets on disk: the GitHub client secret (GitHubBrowserSignIn) and the GitHub token key (GitHubTokens).
 */
final class SecretFiles {
    private SecretFiles() {}

    /** One token from a regular, non-symlink, mode-0600 file of at most 4 KiB (ADR-16). */
    static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Secret file must be a regular non-symlink file: " + file);
        }
        var permissions = Files.getPosixFilePermissions(file);
        if (!permissions.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))) {
            throw new IOException("Secret file must have permissions 0600: " + file);
        }
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) {
            bytes = stream.readNBytes(4097);
        }
        if (bytes.length > 4096) throw new IOException("Secret file exceeds 4096 bytes: " + file);
        String secret = StandardCharsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .strip();
        if (!secret.matches("[A-Za-z0-9_]+")) {
            throw new IOException("Secret file must contain one nonblank token, not Markdown prose: " + file);
        }
        return secret;
    }
}
