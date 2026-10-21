package com.reporead.sync;

import java.time.Instant;

/** The published repository snapshot identified by its commit, size, and publication time. */
public record RepositorySyncResult(long repositoryId, String commitSha, int documentCount, Instant syncedAt) {}
