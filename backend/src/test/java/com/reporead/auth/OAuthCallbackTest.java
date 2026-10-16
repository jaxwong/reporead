package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The OAuth callback and the app's redirect are fixed by reporead.public-url: the Mac's adb-reverse loopback address or
 * an HTTPS origin.
 */
class OAuthCallbackTest {
    @Test
    void loopbackDevelopmentAddressOnTheServerPort() {
        assertEquals(
                "http://127.0.0.1:8081/login/oauth2/code/github",
                GitHubSecurity.callbackUri("http://127.0.0.1:8081", 8081));
    }

    @Test
    void hostedHttpsOrigin() {
        assertEquals(
                "https://reporead.duckdns.org/login/oauth2/code/github",
                GitHubSecurity.callbackUri("https://reporead.duckdns.org", 8081));
    }

    @Test
    void eachServerSignsInOnlyTheAppThatUsesIt() {
        assertEquals(
                "https://reporead.duckdns.org/app/signed-in",
                PendingAppSignIn.appRedirect("https://reporead.duckdns.org"));
        assertEquals("reporead-dev://auth", PendingAppSignIn.appRedirect("http://127.0.0.1:8081"));
    }

    @Test
    void rejectsAddressesGitHubShouldNeverRedirectTo() {
        for (String url : new String[] {
            "http://reporead.duckdns.org", // plain HTTP off loopback
            "http://127.0.0.1:9999", // loopback, but not this server's port
            "https://reporead.duckdns.org/", // path
            "https://reporead.duckdns.org/app", // path
            "https://reporead.duckdns.org?x=1", // query
            "https://user@reporead.duckdns.org",
            "https://",
            "",
        }) {
            var error = assertThrows(IllegalArgumentException.class, () -> GitHubSecurity.callbackUri(url, 8081), url);
            assertTrue(error.getMessage().contains("reporead.public-url"), error::getMessage);
        }
    }
}
