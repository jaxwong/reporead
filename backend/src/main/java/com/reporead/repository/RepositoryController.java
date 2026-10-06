package com.reporead.repository;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.github.GitHubApi;
import com.reporead.sync.RepositorySync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * POST .../connect 1; POST .../sync 2 (see RepositorySync).
 */
@RestController
public class RepositoryController {
    private static final Logger LOG = LoggerFactory.getLogger(RepositoryController.class);
    private final GitHubApi github;
    private final RepositoryConnections connections;
    private final RepositorySync sync;

    public RepositoryController(GitHubApi github, RepositoryConnections connections, RepositorySync sync) {
        this.github = github;
        this.connections = connections;
        this.sync = sync;
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
