package com.reporead;

import java.time.Duration;

/** How far ahead of the server a client's own timestamp may be (phone clocks drift); later ones are rejected. */
public final class ClientClock {
    public static final Duration MAX_AHEAD = Duration.ofMinutes(5);

    private ClientClock() {}
}
