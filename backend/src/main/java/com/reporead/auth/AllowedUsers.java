package com.reporead.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The GitHub users this server serves (ADR-16). RepoRead is personal, and the hosted backend is on the internet with a
 * public GitHub App, so GitHub's authorization alone would let any GitHub user sign in, store a GitHub token here, and
 * use the server. Checked when GitHub sign-in completes, before anything is stored, and on every API request, so
 * removing someone also ends their existing sessions. Configuration rather than code: the list changes without a
 * release (for example to let someone try the hosted app). Required; missing or malformed configuration fails startup.
 */
@Component
final class AllowedUsers {
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");
    private final Set<Long> githubUserIds;

    AllowedUsers(@Value("${reporead.allowed-github-user-ids}") String ids) {
        var parsed = new HashSet<Long>();
        for (String id : ids.split(",", -1)) {
            if (!ID.matcher(id.strip()).matches()) {
                throw new IllegalArgumentException("reporead.allowed-github-user-ids must be comma-separated positive GitHub user ids, not: " + ids);
            }
            parsed.add(Long.parseLong(id.strip()));
        }
        this.githubUserIds = Set.copyOf(parsed);
    }

    boolean allows(long githubUserId) {
        return githubUserIds.contains(githubUserId);
    }
}
