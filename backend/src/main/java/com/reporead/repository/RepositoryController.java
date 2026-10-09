package com.reporead.repository;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.github.GitHubApi;
import com.reporead.sync.RepositorySync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * External-call ceilings (GitHub user-token requests per operation):
 * GET /api/repositories 0; GET /api/repositories/available 1 + installations (at most 11);
 * POST .../connect 1; POST .../sync 2 + at most 8 (see RepositorySync); GET .../stored-data 0; DELETE (disconnect) 0.
 */
@RestController
public class RepositoryController {
    private static final Logger LOG = LoggerFactory.getLogger(RepositoryController.class);
    private final GitHubApi github;
    private final RepositoryConnections connections;
    private final RepositorySync sync;
    private final ConnectionData data;
    private final TransactionTemplate transaction;

    public RepositoryController(GitHubApi github, RepositoryConnections connections, RepositorySync sync, ConnectionData data,
                                TransactionTemplate transaction) {
        this.github = github;
        this.connections = connections;
        this.sync = sync;
        this.data = data;
        this.transaction = transaction;
    }

    /** What disconnecting this repository would delete, for the confirmation. */
    @GetMapping("/api/repositories/{id}/stored-data")
    ConnectionData.Counts storedData(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        connections.find(user.id(), id).orElseThrow(() -> new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Repository connection not found."));
        return data.count(id);
    }

    /**
     * Disconnects a repository: deletes the connection and everything stored for it in one transaction, serialized with
     * its syncs. GitHub is not modified, and the GitHub App stays installed until the user removes it on GitHub.
     */
    @DeleteMapping("/api/repositories/{id}")
    ConnectionData.Deleted disconnect(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        var deleted = transaction.execute(status -> {
            connections.lockForSync(user.id(), id);
            return data.delete(id);
        });
        LOG.info("Repository disconnected; userId={} connectionId={} documents={} readingStates={} bookmarks={} highlights={} cards={}",
            user.id(), id, deleted.documentIds().size(), deleted.readingStates(), deleted.bookmarks(), deleted.highlights(), deleted.cards());
        return deleted;
    }

    record Connected(List<RepositoryConnections.Connection> repositories) {}

    @GetMapping("/api/repositories")
    Connected connected(@AuthenticationPrincipal AppUser user) {
        return new Connected(connections.list(user.id()));
    }

    record Available(long githubRepositoryId, long installationId, String fullName, boolean privateRepository,
                     String defaultBranch, Long connectionId) {}
    record AvailableList(List<Available> repositories) {}

    @GetMapping("/api/repositories/available")
    AvailableList available(@AuthenticationPrincipal AppUser user) {
        String token = github.userToken(user.githubUserId());
        var connected = connections.connectedIds(user.id());
        var result = new ArrayList<Available>();
        var seen = new HashSet<Long>();
        for (long installationId : github.installations(token)) {
            for (var repository : github.repositories(token, installationId)) {
                if (!seen.add(repository.id())) {
                    throw new ApiFailure(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub listed one repository in two installations.");
                }
                result.add(new Available(repository.id(), installationId, repository.fullName(), repository.privateRepository(),
                    repository.defaultBranch(), connected.get(repository.id())));
            }
        }
        return new AvailableList(List.copyOf(result));
    }

    record ConnectRequest(Long installationId) {}

    /** The client names a repository; GitHub, not the client, decides whether this user may connect it. */
    @PostMapping("/api/repositories/{githubRepositoryId}/connect")
    RepositoryConnections.Connection connect(@AuthenticationPrincipal AppUser user, @PathVariable long githubRepositoryId,
                                             @RequestBody ConnectRequest body) {
        if (githubRepositoryId <= 0 || body.installationId() == null || body.installationId() <= 0) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REPOSITORY", "githubRepositoryId and installationId must be positive integers.");
        }
        String token = github.userToken(user.githubUserId());
        var repository = github.repositories(token, body.installationId()).stream()
            .filter(candidate -> candidate.id() == githubRepositoryId).findFirst()
            .orElseThrow(() -> new ApiFailure(HttpStatus.FORBIDDEN, "REPOSITORY_NOT_AUTHORIZED",
                "This repository is not available to you through the RepoRead GitHub App installation."));
        long id = connections.connect(user.id(), repository);
        LOG.info("Repository connected; userId={} connectionId={} githubRepositoryId={}", user.id(), id, githubRepositoryId);
        return connections.find(user.id(), id).orElseThrow();
    }

    @PostMapping("/api/repositories/{id}/sync")
    RepositorySync.Result sync(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return sync.sync(user, id);
    }
}
