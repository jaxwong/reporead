package com.reporead;

import java.time.Duration;
import java.time.Instant;

/**
 * Which timestamps from a client's own clock the server accepts: from 1970, which every phone clock is past and
 * PostgreSQL can store, through MAX_AHEAD past the server's now, as phone clocks drift.
 */
public final class ClientClock {
    public static final Duration MAX_AHEAD = Duration.ofMinutes(5);

    private ClientClock() {}

    public static boolean accepts(Instant at) {
        return !at.isBefore(Instant.EPOCH) && !at.isAfter(Instant.now().plus(MAX_AHEAD));
    }
}
