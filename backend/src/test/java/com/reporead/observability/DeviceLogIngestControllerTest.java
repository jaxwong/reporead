package com.reporead.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import com.reporead.auth.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** The app's log lines reach the server's log, apart from its own lines and unable to pose as them. */
@ExtendWith(OutputCaptureExtension.class)
class DeviceLogIngestControllerTest extends SharedSpringTest {
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
        return line("0.1.433", level, message);
    }

    private static String line(String version, String level, String message) {
        return "{\"at\":\"2026-10-21T06:02:11.123Z\",\"appVersion\":\"" + version + "\",\"level\":\"" + level
                + "\",\"message\":\"" + message + "\"}";
    }

    /**
     * A batch can hold lines an older run left, sent by the updated app; each still names the version that logged it.
     */
    @Test
    void eachLineIsLoggedAtItsLevelWithItsVersionAndThePhonesTime(CapturedOutput log) throws Exception {
        upload("{\"lines\":["
                        + line("0.1.432", "INFO", "Reader ready; documentId=7")
                        + ","
                        + line("0.1.433", "WARN", "Backend refused; status=404 code=NOT_FOUND requestId=test-only-id")
                        + "]}")
                .andExpect(status().isNoContent());

        String lines = log.getAll();
        assertTrue(deviceLogLine(lines, " INFO ", "0.1.432", "Reader ready; documentId=7"), lines);
        assertTrue(
                deviceLogLine(
                        lines,
                        " WARN ",
                        "0.1.433",
                        "Backend refused; status=404 code=NOT_FOUND requestId=test-only-id"),
                lines);
    }

    /** Whether [log] has the device's line [message] at [level], under the ingest logger, with [version] and time. */
    private static boolean deviceLogLine(String log, String level, String version, String message) {
        return log.lines()
                .anyMatch(line -> line.contains(level)
                        && line.contains("DeviceLogIngestController ")
                        && line.endsWith(": Device log line; userId=1 version=" + version
                                + " at=2026-10-21T06:02:11.123Z " + message));
    }

    @Test
    void appTextCannotStartALineThatReadsAsTheServers(CapturedOutput log) throws Exception {
        upload("{\"lines\":["
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
            "{\"lines\":[{\"at\":\"2026-10-21T06:02:11Z\",\"level\":\"INFO\",\"message\":\"test-only\"}]}",
            "{\"lines\":[" + line("0.1 433", "INFO", "test-only") + "]}",
            "{\"lines\":[]}",
            "{\"lines\":[{\"at\":\"2026-10-21T06:02:11Z\",\"appVersion\":\"0.1.433\",\"message\":\"test-only\"}]}",
            "{\"lines\":[" + line("DEBUG", "test-only") + "]}"
        }) {
            upload(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertFalse(log.getAll().contains("Device log line;"), log.getAll());
    }

    @Test
    void onlyASignedInAppCanUpload() throws Exception {
        mvc.perform(post("/api/app-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + line("INFO", "test-only") + "]}"))
                .andExpect(status().isUnauthorized());
    }
}
