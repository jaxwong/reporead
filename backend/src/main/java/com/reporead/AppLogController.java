package com.reporead;

import com.reporead.ApiFailure.Code;
import com.reporead.auth.AppUser;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The app's own log lines, written to the server's log under this class's logger, so the phone's lines are read on the
 * server without adb and kept apart from the server's lines by logger name. No GitHub calls and nothing stored: the log
 * is a projection (ADR-21). A batch is at most one request body (RequestBodyLimit), which the app sizes its stored
 * lines to. The lines arrive when the app next uploads, so each carries the phone's own time.
 */
@RestController
public class AppLogController {
    private static final Logger LOG = LoggerFactory.getLogger(AppLogController.class);
    private static final Pattern APP_VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,64}");
    /** Control characters other than tab and newline, which could rewrite a terminal showing the log. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t\\n]]");

    enum LineLevel {
        INFO,
        WARN,
        ERROR
    }

    /** The version is per line: lines a run left can be sent by the next, newer version. */
    record Line(Instant at, String appVersion, LineLevel level, String message) {}

    record UploadRequest(List<Line> lines) {}

    @PostMapping("/api/app-logs")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void upload(@AuthenticationPrincipal AppUser user, @RequestBody UploadRequest body) {
        validate(body);
        for (Line line : body.lines()) {
            LOG.atLevel(Level.valueOf(line.level().name()))
                    .log(
                            "App line; userId={} version={} at={} {}",
                            user.id(),
                            line.appVersion(),
                            line.at(),
                            readable(line.message()));
        }
    }

    /**
     * A line's continuation lines are indented, as a stack trace's frames are, so no app text can start a line that
     * reads as the server's own.
     */
    private static String readable(String message) {
        String visible = CONTROL.matcher(message.replace("\r\n", "\n"))
                .replaceAll(
                        match -> String.format("\\\\u%04x", (int) match.group().charAt(0)));
        return visible.replace("\n", "\n\t");
    }

    private static void validate(UploadRequest body) {
        boolean valid = body.lines() != null
                && !body.lines().isEmpty()
                && body.lines().stream()
                        .allMatch(line -> line != null
                                && line.at() != null
                                && line.appVersion() != null
                                && APP_VERSION.matcher(line.appVersion()).matches()
                                && line.level() != null
                                && line.message() != null);
        if (!valid) {
            throw new ApiFailure(
                    Code.INVALID_REQUEST,
                    "An app log upload requires at least one line, each with a time, an app version (1-64 letters, digits, '.', '+' or '-'), a level and a message.");
        }
    }
}
