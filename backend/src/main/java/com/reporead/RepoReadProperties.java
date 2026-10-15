package com.reporead;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * The reporead.* settings in application.properties, bound once at startup. Every one is required and has no default;
 * the classes that use a value check its meaning (AllowedUsers, GitHubSecurity, GitHubTokens).
 */
@ConfigurationProperties("reporead")
public record RepoReadProperties(String publicUrl, String allowedGithubUserIds, GitHub github) {
    public RepoReadProperties {
        required("reporead.public-url", publicUrl);
        required("reporead.allowed-github-user-ids", allowedGithubUserIds);
        if (github == null) throw new IllegalArgumentException("reporead.github.* must be set");
    }

    public record GitHub(String clientId, Path clientSecretFile, Path tokenKeyFile) {
        public GitHub {
            required("reporead.github.client-id", clientId);
            required("reporead.github.client-secret-file", clientSecretFile == null ? null : clientSecretFile.toString());
            required("reporead.github.token-key-file", tokenKeyFile == null ? null : tokenKeyFile.toString());
        }
    }

    /**
     * Unlike @Value, the binder keeps an unresolved ${...} as the literal value, so an environment variable that is not
     * set would otherwise start the server with the placeholder as, for example, its GitHub client id.
     */
    private static void required(String name, String value) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            throw new IllegalArgumentException(name + " must be set (see application.properties), not: " + value);
        }
    }
}
