package com.reporead.repository;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.auth.AppUser;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * External-call ceilings (GitHub user-token requests per operation): GET /api/repositories/available 1 + installations
 * (at most 11); POST .../connect 1. The connected repositories are read with the library (Library).
 */
@RestController
public class RepositoryController {
    private static final Logger LOG = LoggerFactory.getLogger(RepositoryController.class);
    private final RepositoryConnections connections;

    public RepositoryController(RepositoryConnections connections) {
        this.connections = connections;
    }

    record AvailableList(List<RepositoryConnections.Available> repositories) {}

    @GetMapping("/api/repositories/available")
    AvailableList available(@AuthenticationPrincipal AppUser user) {
        return new AvailableList(connections.available(user));
    }

    record ConnectRequest(Long installationId) {}

    /** The client names a repository; GitHub, not the client, decides whether this user may connect it. */
    @PostMapping("/api/repositories/{githubRepositoryId}/connect")
    RepositoryConnections.Connection connect(
            @AuthenticationPrincipal AppUser user,
            @PathVariable long githubRepositoryId,
            @RequestBody ConnectRequest body) {
        if (githubRepositoryId <= 0 || body.installationId() == null || body.installationId() <= 0) {
            throw new ApiFailure(
                    Code.INVALID_REPOSITORY, "githubRepositoryId and installationId must be positive integers.");
        }
        var connection = connections.connect(user, githubRepositoryId, body.installationId());
        LOG.info(
                "Repository connected; userId={} connectionId={} githubRepositoryId={}",
                user.id(),
                connection.id(),
                githubRepositoryId);
        return connection;
    }
}
