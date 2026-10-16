package com.reporead;

import static org.junit.jupiter.api.Assertions.*;

import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which failures are RepoRead's answer ({code, message}) and which are not. The app treats a RepoRead 400, 404, 409,
 * 410 or 422 as the server refusing that item for good, and drops or marks the pending change (Sync.refusesItem); a
 * body without a code is not RepoRead's answer and leaves it pending. So a route, method or body type the server does
 * not have, as when the app is newer than the server, must not carry a code. Through a real socket, because that body
 * is Spring Boot's /error page, which MockMvc does not render. Requests send the app's own headers (Api.kt).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiErrorsTest {
    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
        // On a random port the loopback form of the public URL cannot name it; any HTTPS origin can.
        properties.add("reporead.public-url", () -> "https://reporead.test");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient db;

    @Autowired
    AppSessions sessions;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    ClientRegistrationRepository registrations;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String bearer;

    @BeforeEach
    void signIn() {
        TestEnvironment.reset(db);
        bearer = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_UNUSED_TOKEN");
    }

    private record Answer(int status, JsonNode body) {}

    private Answer send(String method, String path, String contentType, String body, boolean signedIn)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) request.header("Content-Type", contentType);
        if (signedIn) request.header("Authorization", "Bearer " + bearer);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(
                response.statusCode(),
                response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body()));
    }

    @Test
    void routesMethodsAndBodyTypesTheServerDoesNotHaveAreNotRepoReadsAnswer() throws Exception {
        var answers = new Answer[] {
            send("GET", "/api/does-not-exist", null, null, true),
            // Spring matches no trailing slash, so this is an unknown route too, not the document list.
            send("GET", "/api/repositories/1/documents/", null, null, true),
            send("PUT", "/api/bookmarks", "application/json", "{}", true),
            send("POST", "/api/documents/1/annotations", "text/plain", "{}", true)
        };
        int[] statuses = {404, 404, 405, 415};
        for (int i = 0; i < answers.length; i++) {
            assertEquals(statuses[i], answers[i].status(), answers[i].body().toString());
            assertFalse(answers[i].body().has("code"), "no RepoRead code: " + answers[i].body());
        }
    }

    @Test
    void anUnknownRouteIsUnauthorizedWithoutASession() throws Exception {
        // Without a session nothing under /api is reachable, so the answer does not reveal which routes exist.
        var answer = send("GET", "/api/does-not-exist", null, null, false);
        assertEquals(401, answer.status());
        assertFalse(answer.body().has("code"));
    }

    @Test
    void requestsARouteCannotReadAndAbsentIdsAreRepoReadsAnswer() throws Exception {
        assertCode(send("GET", "/api/documents/999999/content", null, null, true), 404, "NOT_FOUND");
        assertCode(
                send("PUT", "/api/documents/1/reading-state", "application/json", "{", true), 400, "INVALID_REQUEST");
        assertCode(send("DELETE", "/api/annotations/1?expectedVersion=one", null, null, true), 400, "INVALID_REQUEST");
        assertCode(send("PUT", "/api/documents/abc/bookmark", null, null, true), 400, "INVALID_REQUEST");
    }

    private static void assertCode(Answer answer, int status, String code) {
        assertEquals(status, answer.status(), answer.body().toString());
        assertEquals(code, answer.body().path("code").asString(), answer.body().toString());
        assertFalse(
                answer.body().path("message").asString().isBlank(),
                answer.body().toString());
    }
}
