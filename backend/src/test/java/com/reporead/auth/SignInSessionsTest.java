package com.reporead.auth;

import com.reporead.TestEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sign-in keeps the app's challenge in a browser session for someone the server has not authenticated, so how many
 * can exist at once is bounded. Through a real socket, because the bound is Tomcat's session manager.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignInSessionsTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
        // On a random port the loopback form of the public URL cannot name it; any HTTPS origin can.
        properties.add("reporead.public-url", () -> "https://reporead.test");
    }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    /** A new browser each time: no cookie is sent, so every sign-in opens its own session. */
    private HttpResponse<String> startSignIn() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/app/sign-in?code_challenge="
            + TestSessions.challenge(TestSessions.verifier()))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void signInsBeyondTheCapAreRefusedInsteadOfHeldInMemory() throws Exception {
        for (int i = 0; i < GitHubSecurity.MAX_SIGN_IN_SESSIONS; i++) assertEquals(302, startSignIn().statusCode(), "sign-in " + i);
        var refused = startSignIn();
        assertEquals(503, refused.statusCode());
        assertTrue(refused.body().contains("\"code\":\"SIGN_IN_BUSY\""), refused.body());
    }
}
