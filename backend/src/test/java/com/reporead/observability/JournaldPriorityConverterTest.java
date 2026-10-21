package com.reporead.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;

/** Which journald priority each log level is filed at; the prefix itself is written only under systemd. */
class JournaldPriorityConverterTest {
    @Test
    void eachLevelHasItsSyslogPriority() {
        assertEquals("<3>", JournaldPriorityConverter.prefix(Level.ERROR));
        assertEquals("<4>", JournaldPriorityConverter.prefix(Level.WARN));
        assertEquals("<6>", JournaldPriorityConverter.prefix(Level.INFO));
        assertEquals("<7>", JournaldPriorityConverter.prefix(Level.DEBUG));
        assertEquals("<7>", JournaldPriorityConverter.prefix(Level.TRACE));
    }
}
