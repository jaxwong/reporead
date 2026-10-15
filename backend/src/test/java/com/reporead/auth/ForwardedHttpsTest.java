package com.reporead.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.reporead.TestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The hosted server sits behind a local HTTPS proxy (Caddy) that sends X-Forwarded-Proto. Through a real socket,
 * because MockMvc bypasses Tomcat's handling of forwarded headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedHttpsTest {
    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
        properties.add("reporead.public-url", () -> "https://reporead.test");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<Void> startSignIn(String forwardedProto) throws Exception {
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/app/sign-in?scheme=reporead&code_challenge=" + challenge));
        if (forwardedProto != null) request.header("X-Forwarded-Proto", forwardedProto);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void behindTheHttpsProxyRedirectsStayOnHttpsAndTheSessionCookieIsSecure() throws Exception {
        var response = startSignIn("https");
        assertEquals(302, response.statusCode());
        assertTrue(
                response.headers().firstValue("Location").orElseThrow().startsWith("https://"),
                () -> "sign-in redirect left HTTPS: " + response.headers().firstValue("Location"));
        assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("Secure"));
    }

    @Test
    void withoutAProxyTheLoopbackServerKeepsPlainHttp() throws Exception {
        var response = startSignIn(null);
        assertEquals(302, response.statusCode());
        assertTrue(
                response.headers().firstValue("Location").orElseThrow().startsWith("http://127.0.0.1:" + port + "/"));
        assertFalse(response.headers().firstValue("Set-Cookie").orElseThrow().contains("Secure"));
    }
}
