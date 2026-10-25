package com.reporead;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * A database that accepts the TCP connection but never answers must fail startup, not hang it. Seen on the hosted VM
 * when a firewalld reload wiped Podman's NAT rules: connections reached a placeholder socket and the backend sat in
 * "HikariPool-1 - Starting..." indefinitely. Not a Spring test context: the application is expected not to start.
 */
class DatabaseTimeoutTest {
    @Test
    void startupFailsWhenTheDatabaseAcceptsButNeverAnswers() throws Exception {
        var held = new CopyOnWriteArrayList<Socket>();
        try (var silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> acceptAndNeverAnswer(silent, held));
            var secret = TestEnvironment.secretFile("reporead-test-client-secret-", "TEST_ONLY_NOT_A_REAL_SECRET");
            var tokenKey = TestEnvironment.secretFile("reporead-test-token-key-", TestEnvironment.TOKEN_KEY);

            // Command-line arguments outrank application.properties, whose values are unresolved placeholders here.
            // Every required setting is given, so the database is the only thing startup can fail on.
            String[] args = {
                "--server.port=0",
                "--management.server.port=0",
                "--reporead.public-url=https://reporead.test",
                "--reporead.allowed-github-user-ids=42",
                "--reporead.github.client-id=test-only-not-a-github-app",
                "--reporead.github.client-secret-file=" + secret,
                "--reporead.github.token-key-file=" + tokenKey,
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + silent.getLocalPort() + "/reporead",
                "--spring.datasource.username=test-only",
                "--spring.datasource.password=test-only",
            };
            var app = new SpringApplicationBuilder(RepoReadApplication.class);

            long started = System.nanoTime();
            var error = assertTimeoutPreemptively(
                    Duration.ofSeconds(40),
                    () -> assertThrows(Exception.class, () -> app.run(args)),
                    "startup still waiting on a database that never answers");
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertTrue(
                    causes(error).stream().anyMatch(cause -> cause instanceof java.sql.SQLException),
                    () -> "expected an SQLException in the cause chain, got: " + causes(error));
            // Without the 10 s login deadline, the other deadlines still end startup, after 28 s when measured on
            // 2026-10-25; under 20 s shows the login deadline ended it.
            assertTrue(took.compareTo(Duration.ofSeconds(20)) < 0, () -> "startup took " + took + " to fail");
        } finally {
            for (var socket : held) socket.close();
        }
    }

    private static void acceptAndNeverAnswer(ServerSocket server, List<Socket> held) {
        try {
            while (true) held.add(server.accept());
        } catch (IOException ioFailure) {
            // Closing the server socket at the end of the test is how this loop stops; anything else is a test fault.
            if (!server.isClosed()) throw new UncheckedIOException("silent database socket failed", ioFailure);
        }
    }

    private static List<Throwable> causes(Throwable error) {
        var chain = new ArrayList<Throwable>();
        for (Throwable cause = error; cause != null && !chain.contains(cause); cause = cause.getCause())
            chain.add(cause);
        return chain;
    }
}
