package com.reporead.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Which GitHub users this server serves: parsed strictly at startup, enforced before sign-in stores anything. */
class AllowedUsersTest {
    @Test void parsesCommaSeparatedPositiveIds() {
        var allowed = new AllowedUsers(" 42, 84 ");
        assertTrue(allowed.allows(42));
        assertTrue(allowed.allows(84));
        assertFalse(allowed.allows(7));
    }

    @Test void refusesMissingOrMalformedConfigurationAtStartup() {
        for (String ids : new String[] {"", " ", "42,", ",42", "42,,84", "abc", "-1", "0", "4 2", "1234567890123456789"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> new AllowedUsers(ids), ids);
            assertTrue(error.getMessage().contains("reporead.allowed-github-user-ids"), error::getMessage);
        }
    }

    @Test void signInOfAGitHubUserNotOnTheListFailsBeforeAnythingIsStored() {
        var allowed = new AllowedUsers("42");
        var listed = user(42);
        assertSame(listed, GitHubSecurity.allowedOnly(listed, allowed));
        var error = assertThrows(OAuth2AuthenticationException.class, () -> GitHubSecurity.allowedOnly(user(84), allowed));
        assertEquals("access_denied", error.getError().getErrorCode());
    }

    private static DefaultOAuth2User user(long id) {
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), Map.of("id", id, "login", "test-only-user-" + id), "id");
    }
}
