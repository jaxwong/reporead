package com.reporead.auth;

/** The authenticated principal for /api requests: RepoRead user id plus the GitHub identity it was created from. */
public record AppUser(long id, long githubUserId, String login) {}
