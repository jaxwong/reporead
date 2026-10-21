package com.reporead;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The app's log lines reach the server's log, apart from its own lines and unable to pose as them. */
@ExtendWith(OutputCaptureExtension.class)
class AppLogTest extends SharedSpringTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    AppSessions sessions;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    JdbcClient db;

    String bearer;

    @BeforeEach
    void signIn() {
        TestEnvironment.reset(db);
        bearer = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_UNUSED_TOKEN");
    }

    private ResultActions upload(String body) throws Exception {
        return mvc.perform(post("/api/app-logs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String line(String level, String message) {
        return "{\"at\":\"2026-10-21T06:02:11.123Z\",\"level\":\"" + level + "\",\"message\":\"" + message + "\"}";
    }

    @Test
    void eachLineIsLoggedAtItsLevelWithThePhonesTime(CapturedOutput log) throws Exception {
        upload("{\"appVersion\":\"0.1.433\",\"lines\":["
                        + line("INFO", "Reader ready; documentId=7")
                        + ","
                        + line("WARN", "Backend refused; status=404 code=NOT_FOUND requestId=test-only-id")
                        + "]}")
                .andExpect(status().isNoContent());

        String lines = log.getAll();
        assertTrue(appLine(lines, " INFO ", "Reader ready; documentId=7"), lines);
        assertTrue(
                appLine(lines, " WARN ", "Backend refused; status=404 code=NOT_FOUND requestId=test-only-id"), lines);
    }

    /** Whether [log] has the app's line [message] at [level], under the app's logger, with its version and time. */
    private static boolean appLine(String log, String level, String message) {
        return log.lines()
                .anyMatch(line -> line.contains(level)
                        && line.contains(" com.reporead.AppLogController ")
                        && line.endsWith(
                                ": App line; userId=1 version=0.1.433 at=2026-10-21T06:02:11.123Z " + message));
    }

    @Test
    void appTextCannotStartALineThatReadsAsTheServers(CapturedOutput log) throws Exception {
        upload("{\"appVersion\":\"0.1.433\",\"lines\":["
                        + line(
                                "ERROR",
                                "Crash; error=IllegalStateException\\n\\tat a.b(C.kt:1)\\r\\n2026-10-21 INFO forged\\u001b[31m")
                        + "]}")
                .andExpect(status().isNoContent());

        String lines = log.getAll();
        assertTrue(
                lines.contains(
                        "Crash; error=IllegalStateException\n\t\tat a.b(C.kt:1)\n\t2026-10-21 INFO forged\\u001b[31m"),
                lines);
        assertFalse(lines.contains("\n2026-10-21 INFO forged"), lines);
    }

    @Test
    void aBatchTheServerCannotLogIsRefusedAndNothingIsLogged(CapturedOutput log) throws Exception {
        for (String body : new String[] {
            "{\"lines\":[" + line("INFO", "test-only") + "]}",
            "{\"appVersion\":\"0.1 433\",\"lines\":[" + line("INFO", "test-only") + "]}",
            "{\"appVersion\":\"0.1.433\",\"lines\":[]}",
            "{\"appVersion\":\"0.1.433\",\"lines\":[{\"at\":\"2026-10-21T06:02:11Z\",\"message\":\"test-only\"}]}",
            "{\"appVersion\":\"0.1.433\",\"lines\":[" + line("DEBUG", "test-only") + "]}"
        }) {
            upload(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertFalse(log.getAll().contains("App line;"), log.getAll());
    }

    @Test
    void onlyASignedInAppCanUpload() throws Exception {
        mvc.perform(post("/api/app-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appVersion\":\"0.1.433\",\"lines\":[" + line("INFO", "test-only") + "]}"))
                .andExpect(status().isUnauthorized());
    }
}
