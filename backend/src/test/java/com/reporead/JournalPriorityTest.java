package com.reporead;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;

/** Which journald priority each log level is filed at; the prefix itself is written only under systemd. */
class JournalPriorityTest {
    @Test
    void eachLevelHasItsSyslogPriority() {
        assertEquals("<3>", JournalPriority.prefix(Level.ERROR));
        assertEquals("<4>", JournalPriority.prefix(Level.WARN));
        assertEquals("<6>", JournalPriority.prefix(Level.INFO));
        assertEquals("<7>", JournalPriority.prefix(Level.DEBUG));
        assertEquals("<7>", JournalPriority.prefix(Level.TRACE));
    }
}
