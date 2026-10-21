package com.reporead.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every request's log lines and response name the same server-made id, including requests Spring Security refuses and
 * ones that fail unhandled, which only a real socket shows (MockMvc runs neither Tomcat's error handling nor the
 * outermost filter order).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
        // On a random port the loopback form of the public URL cannot name it; any HTTPS origin can.
        properties.add("reporead.public-url", () -> "https://reporead.test");
    }

    /**
     * Test-only: a route that fails with an exception nothing handles, as a server bug would. Spring registers a
     * component nested in a test configuration as part of it, so only this class's context has the route.
     */
    @TestConfiguration
    static class FailingRoute {
        @RestController
        static class Failing {
            @GetMapping("/api/test-only/unhandled")
            String fail() {
                throw new IllegalStateException("test-only unhandled failure");
            }
        }
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
    private String bearer;

    @BeforeEach
    void signIn() {
        TestEnvironment.reset(db);
        bearer = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_UNUSED_TOKEN");
    }

    private HttpResponse<String> get(String path, boolean signedIn) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
        if (signedIn) request.header("Authorization", "Bearer " + bearer);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String requestId(HttpResponse<String> response) {
        String id = response.headers().firstValue(RequestLoggingFilter.HEADER).orElseThrow();
        assertTrue(ID.matcher(id).matches(), id);
        return id;
    }

    /** The log lines that name [id] in their correlation brackets. */
    private static String linesOf(CapturedOutput log, String id) {
        return log.getAll()
                .lines()
                .filter(line -> line.contains("[" + id + "] "))
                .reduce("", (lines, line) -> lines + line + "\n");
    }

    @Test
    void aRefusedRequestsLinesAndResponseNameOneId(CapturedOutput log) throws Exception {
        var refused = get("/api/documents/999999/content", true);
        assertEquals(404, refused.statusCode());

        String lines = linesOf(log, requestId(refused));
        assertTrue(lines.contains("API request failed; method=GET path=/api/documents/999999/content"), lines);
        assertTrue(lines.contains("Request; method=GET target=/api/documents/999999/content status=404"), lines);
    }

    @Test
    void aRequestWithoutASessionIsLoggedWithItsId(CapturedOutput log) throws Exception {
        var unauthorized = get("/api/library", false);
        assertEquals(401, unauthorized.statusCode());

        String lines = linesOf(log, requestId(unauthorized));
        assertTrue(lines.contains("Request; method=GET target=/api/library status=401"), lines);
    }

    @Test
    void eachRequestHasItsOwnId() throws Exception {
        assertNotEquals(requestId(get("/api/library", true)), requestId(get("/api/library", true)));
    }

    @Test
    void theRequestSummaryIncludesTheQuery(CapturedOutput log) throws Exception {
        var image = get("/api/documents/999999/image?commitSha=" + "a".repeat(40) + "&path=private-note.png", true);

        String lines = linesOf(log, requestId(image));
        String requestSummary = lines.lines()
                .filter(line -> line.contains("RequestLoggingFilter "))
                .filter(line -> line.contains("Request; method=GET target=/api/documents/999999/image?"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(lines));
        assertTrue(requestSummary.contains("commitSha=" + "a".repeat(40) + "&path=private-note.png"), requestSummary);
    }

    @Test
    void anUnhandledFailureIsLoggedWithItsIdAndTheIdIsReturned(CapturedOutput log) throws Exception {
        var failed = get("/api/test-only/unhandled", true);
        assertEquals(500, failed.statusCode());

        String lines = linesOf(log, requestId(failed));
        assertTrue(
                lines.contains("Request failed unhandled; method=GET target=/api/test-only/unhandled durationMs="),
                lines);
        assertTrue(lines.contains("error=IllegalStateException"), lines);
        assertTrue(
                lines.lines().anyMatch(l -> l.contains(" ERROR ") && l.contains("Request failed unhandled;")), lines);
    }
}
