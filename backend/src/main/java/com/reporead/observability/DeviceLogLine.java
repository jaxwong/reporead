package com.reporead.observability;

import java.time.Instant;

/** One durable line from the Android device; its app version is the version that originally wrote it. */
record DeviceLogLine(Instant at, String appVersion, DeviceLogLevel level, String message) {}
