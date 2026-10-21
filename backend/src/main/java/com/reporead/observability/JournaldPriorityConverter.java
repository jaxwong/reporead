package com.reporead.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * journald's priority prefix (sd-daemon(3)) for each log line, `<3>` for ERROR to `<7>` for DEBUG: journald strips it
 * and files the line at that priority, so the VM's log viewers can filter the backend's (and the app's) warnings and
 * errors; without it every stdout line is "info". Only when stdout is the journal, which systemd announces with
 * JOURNAL_STREAM, so a terminal or test output shows no prefix. A stack trace's later lines carry none and stay "info".
 * Used by logback-spring.xml.
 */
public class JournaldPriorityConverter extends ClassicConverter {
    private static final boolean TO_JOURNAL = System.getenv("JOURNAL_STREAM") != null;

    @Override
    public String convert(ILoggingEvent event) {
        return TO_JOURNAL ? prefix(event.getLevel()) : "";
    }

    static String prefix(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> "<3>";
            case Level.WARN_INT -> "<4>";
            case Level.INFO_INT -> "<6>";
            default -> "<7>";
        };
    }
}
