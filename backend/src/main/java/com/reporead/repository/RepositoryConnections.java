package com.reporead.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.auth.AppSessions;
import com.reporead.auth.AppUser;
import com.reporead.auth.GitHubTokenLifecycle;
import com.reporead.github.GitHubApi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns repository_connections, and which repositories a user may connect: GitHub decides that, through the user's own
 * token, never the client. Every read is scoped to the requesting user.
 */
@Component
public class RepositoryConnections {
    private final JdbcClient db;
    private final TransactionTemplate transaction;
    private final AppSessions sessions;
    private final GitHubApi github;
    private final GitHubTokenLifecycle credentials;

    public RepositoryConnections(
            JdbcClient db,
            TransactionTemplate transaction,
            AppSessions sessions,
            GitHubApi github,
            GitHubTokenLifecycle credentials) {
        this.db = db;
        this.transaction = transaction;
        this.sessions = sessions;
        this.github = github;
        this.credentials = credentials;
    }

    public record Connection(
            long id,
            long githubRepositoryId,
            String owner,
            String name,
            String defaultBranch,
            @Nullable String lastSyncedCommitSha,
            int documentCount) {
        @JsonProperty("fullName")
        public String fullName() {
            return owner + "/" + name;
        }
    }

    private static final String SELECT = """
        SELECT c.id, c.github_repository_id, c.owner, c.name, c.default_branch, c.last_synced_commit_sha,
               (SELECT count(*) FROM documents d WHERE d.repository_connection_id = c.id AND d.deleted_at IS NULL) AS document_count
        FROM repository_connections c""";

    public List<Connection> list(long userId) {
        return db.sql(SELECT + " WHERE c.user_id = :userId ORDER BY c.owner, c.name")
                .param("userId", userId)
                .query(Connection.class)
                .list();
    }

    public Optional<Connection> find(long userId, long connectionId) {
        return db.sql(SELECT + " WHERE c.user_id = :userId AND c.id = :id")
                .param("userId", userId)
                .param("id", connectionId)
                .query(Connection.class)
                .optional();
    }

    /**
     * A repository the user's token reaches through one of the app's installations, and its connection if connected.
     * {@code outdated}: connected, but GitHub now names the repository or its default branch differently than the
     * connection does, so its calls fail with REPOSITORY_RENAMED until it is connected again. Never true unconnected.
     */
    public record Available(
            long githubRepositoryId,
            long installationId,
            String fullName,
            boolean privateRepository,
            String defaultBranch,
            @Nullable Long connectionId,
            boolean outdated) {}

    /**
     * Every repository the user can connect: each installation's complete list (1 + installations GitHub requests, at
     * most 11). One repository listed in two installations is an invalid answer from GitHub.
     */
    List<Available> available(AppUser user) {
        String token = credentials.githubUserToken(user.githubUserId());
        var connected = connected(user.id());
        var result = new ArrayList<Available>();
        var seen = new HashSet<Long>();
        for (long installationId : github.installations(token)) {
            for (var repository : github.repositories(token, installationId)) {
                if (!seen.add(repository.id())) {
                    throw new ApiFailure(
                            Code.GITHUB_INVALID_RESPONSE, "GitHub listed one repository in two installations.");
                }
                var connection = connected.get(repository.id());
                result.add(new Available(
                        repository.id(),
                        installationId,
                        repository.fullName(),
                        repository.privateRepository(),
                        repository.defaultBranch(),
                        connection == null ? null : connection.id(),
                        connection != null && !connection.namesMatch(repository)));
            }
        }
        return List.copyOf(result);
    }

    /** A connection's id and the names GitHub gave when it was last connected. */
    private record Saved(long id, String owner, String name, String defaultBranch) {
        boolean namesMatch(GitHubApi.Repository repository) {
            return owner.equals(repository.owner())
                    && name.equals(repository.name())
                    && defaultBranch.equals(repository.defaultBranch());
        }
    }

    /** GitHub repository id → saved connection, for marking which available repositories are connected or outdated. */
    private Map<Long, Saved> connected(long userId) {
        return db
                .sql("""
                        SELECT github_repository_id, id, owner, name, default_branch FROM repository_connections
                        WHERE user_id = :userId""")
                .param("userId", userId)
                .query((row, n) -> Map.entry(
                        row.getLong("github_repository_id"),
                        new Saved(
                                row.getLong("id"),
                                row.getString("owner"),
                                row.getString("name"),
                                row.getString("default_branch"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Connects the repository {@code githubRepositoryId} if GitHub lists it in installation {@code installationId} for
     * this user (1 GitHub request); otherwise 403 REPOSITORY_NOT_AUTHORIZED. Connecting twice returns the same
     * connection, refreshed with GitHub's current metadata. Account deletion locks the user before listing the
     * connections it deletes, so under the user's lock this connection is either listed and deleted with the rest, or
     * refused because the account is already gone. It is read back under the same lock, so a deletion cannot remove it
     * before it is returned.
     */
    Connection connect(AppUser user, long githubRepositoryId, long installationId) {
        String token = credentials.githubUserToken(user.githubUserId());
        var repository = github.repositories(token, installationId).stream()
                .filter(candidate -> candidate.id() == githubRepositoryId)
                .findFirst()
                .orElseThrow(() -> new ApiFailure(
                        Code.REPOSITORY_NOT_AUTHORIZED,
                        "This repository is not available to you through the RepoRead GitHub App installation."));
        long userId = user.id();
        return transaction.execute(status -> {
            if (!sessions.lockUserForWrite(userId)) {
                throw new ApiFailure(Code.SIGN_IN_REQUIRED, "This RepoRead account was deleted; sign in again.");
            }
            long id = db.sql("""
                    INSERT INTO repository_connections (user_id, github_repository_id, owner, name, default_branch)
                    VALUES (:userId, :githubRepositoryId, :owner, :name, :defaultBranch)
                    ON CONFLICT (user_id, github_repository_id) DO UPDATE
                    SET owner = excluded.owner, name = excluded.name, default_branch = excluded.default_branch
                    RETURNING id""")
                    .param("userId", userId)
                    .param("githubRepositoryId", repository.id())
                    .param("owner", repository.owner())
                    .param("name", repository.name())
                    .param("defaultBranch", repository.defaultBranch())
                    .query(Long.class)
                    .single();
            return find(userId, id).orElseThrow();
        });
    }

    /**
     * The commit a connection last published (null before its first sync) and the branch it syncs from, read under its
     * lock.
     */
    public record Checkpoint(String commitSha, String defaultBranch) {}

    /**
     * Serializes syncs, reconnection (whose upsert locks the same row) and disconnection of one connection; must run
     * inside their transaction. Returns the published checkpoint, read under the lock. A connection disconnected
     * meanwhile is not found.
     */
    public Checkpoint lockForSync(long userId, long connectionId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("lockForSync(connectionId=" + connectionId
                    + ") ran outside a transaction, where its row lock is released as soon as it is taken");
        }
        return db.sql("""
                        SELECT last_synced_commit_sha AS commit_sha, default_branch FROM repository_connections
                        WHERE id = :id AND user_id = :userId FOR UPDATE""")
                .param("id", connectionId)
                .param("userId", userId)
                .query(Checkpoint.class)
                .optional()
                .orElseThrow(ApiFailure::notFound);
    }

    /**
     * Holds off a sync or disconnection of the connection that owns {@code documentId} while a write referencing the
     * document commits; must run inside that write's transaction, before it writes. Writes share the lock, so they do
     * not wait for each other. Without it, a row committed after a disconnect's deletes ran would break the disconnect.
     * A document disconnected meanwhile is not found.
     */
    public void lockForDocumentWrite(long userId, long documentId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("lockForDocumentWrite(documentId=" + documentId
                    + ") ran outside a transaction, where its row lock is released as soon as it is taken");
        }
        db.sql("""
                SELECT c.id FROM repository_connections c JOIN documents d ON d.repository_connection_id = c.id
                WHERE d.id = :documentId AND c.user_id = :userId FOR SHARE OF c""")
                .param("documentId", documentId)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElseThrow(ApiFailure::notFound);
    }

    /** Deletes the connection row; its documents and their reading and annotation rows must already be deleted. */
    public void delete(long connectionId) {
        db.sql("DELETE FROM repository_connections WHERE id = :id")
                .param("id", connectionId)
                .update();
    }

    public void markSynced(long connectionId, String commitSha) {
        db.sql("UPDATE repository_connections SET last_synced_commit_sha = :commitSha WHERE id = :id")
                .param("commitSha", commitSha)
                .param("id", connectionId)
                .update();
    }
}
