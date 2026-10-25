package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.reporead.RepoReadProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Which GitHub users this server serves, parsed strictly at startup. GitHubLoginTest shows sign-in refusing anyone else
 * before anything is stored.
 */
class AllowedUsersTest {
    @Test
    void parsesCommaSeparatedPositiveIds() {
        var allowed = allowedUsers(" 42, 84 ");
        assertTrue(allowed.allows(42));
        assertTrue(allowed.allows(84));
        assertFalse(allowed.allows(7));
    }

    /** A missing or blank list never reaches AllowedUsers: RepoReadProperties refuses it (RepoReadPropertiesTest). */
    @Test
    void refusesMalformedConfigurationAtStartup() {
        for (String ids : new String[] {"42,", ",42", "42,,84", "abc", "-1", "0", "4 2", "1234567890123456789"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> allowedUsers(ids), ids);
            assertTrue(
                    error.getMessage()
                            .contains(
                                    "reporead.allowed-github-user-ids must be comma-separated positive GitHub user ids"),
                    error::getMessage);
        }
    }

    private static AllowedUsers allowedUsers(String ids) {
        var github = new RepoReadProperties.GitHub(
                "test-only-not-a-github-app", Path.of("test-only-unused"), Path.of("test-only-unused"));
        return new AllowedUsers(new RepoReadProperties("https://reporead.test", ids, github));
    }
}
