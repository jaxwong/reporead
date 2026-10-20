package com.reporead;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** The loopback management port (ADR-21), through a real socket: MockMvc does not serve a separate management port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagementEndpointsTest {
    // Chosen once: a property supplier runs on every read, and ApiSecurity's management filter chain matches the
    // configured port number, so 0 (a random port) would never match.
    private static final int MANAGEMENT_PORT = freePort();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
        properties.add("reporead.public-url", () -> "https://reporead.test");
        properties.add("management.server.port", () -> MANAGEMENT_PORT);
    }

    @LocalManagementPort
    int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void infoNamesTheCommitTheJarWasBuiltFrom() throws Exception {
        var response = get("/actuator/info");
        assertEquals(200, response.statusCode(), response::body);
        var build = JsonMapper.builder().build().readTree(response.body()).path("build");
        assertEquals("backend", build.path("artifact").asString(), response::body);
        assertEquals(gitHead(), build.path("commit").asString(), response::body);
    }

    @Test
    void onlyHealthMetricsAndInfoAreExposed() throws Exception {
        assertEquals(200, get("/actuator/health").statusCode());
        assertEquals(200, get("/actuator/metrics").statusCode());
        for (String hidden :
                new String[] {"/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/loggers"}) {
            assertEquals(404, get(hidden).statusCode(), hidden);
        }
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new UncheckedIOException("No free loopback port for the test management server", error);
        }
    }

    private static String gitHead() throws Exception {
        var git = new ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();
        String output = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, git.waitFor(), output);
        return output;
    }
}
