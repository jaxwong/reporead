package com.reporead.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.reporead.ApiFailure;
import com.reporead.github.GitHubApi;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Owns repository_connections. Every read is scoped to the requesting user. */
@Component
public class RepositoryConnections {
    private final JdbcClient db;

    public RepositoryConnections(JdbcClient db) {
        this.db = db;
    }

    public record Connection(long id, long githubRepositoryId, long installationId, String owner, String name,
                             String defaultBranch, String lastSyncedCommitSha, Instant lastSyncedAt, int documentCount) {
        @JsonProperty("fullName")
        public String fullName() { return owner + "/" + name; }
    }

    private static final String SELECT = """
        select c.id, c.github_repository_id, c.installation_id, c.owner, c.name, c.default_branch,
               c.last_synced_commit_sha, c.last_synced_at,
               (select count(*) from documents d where d.repository_connection_id = c.id and d.deleted_at is null)
        from repository_connections c""";

    public List<Connection> list(long userId) {
        return db.sql(SELECT + " where c.user_id = :userId order by c.owner, c.name")
            .param("userId", userId).query(RepositoryConnections::connection).list();
    }

    public Optional<Connection> find(long userId, long connectionId) {
        return db.sql(SELECT + " where c.user_id = :userId and c.id = :id")
            .param("userId", userId).param("id", connectionId).query(RepositoryConnections::connection).optional();
    }

    /** GitHub repository id → connection id, for marking which available repositories are already connected. */
    Map<Long, Long> connectedIds(long userId) {
        return db.sql("select github_repository_id, id from repository_connections where user_id = :userId")
            .param("userId", userId).query((row, n) -> Map.entry(row.getLong(1), row.getLong(2))).list()
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Connecting twice returns the same connection, refreshed with GitHub's current metadata. */
    long connect(long userId, GitHubApi.Repository repository) {
        return db.sql("""
                insert into repository_connections (user_id, github_repository_id, installation_id, owner, name, default_branch)
                values (:userId, :githubRepositoryId, :installationId, :owner, :name, :defaultBranch)
                on conflict (user_id, github_repository_id) do update
                set installation_id = excluded.installation_id, owner = excluded.owner,
                    name = excluded.name, default_branch = excluded.default_branch
                returning id""")
            .param("userId", userId).param("githubRepositoryId", repository.id())
            .param("installationId", repository.installationId()).param("owner", repository.owner())
            .param("name", repository.name()).param("defaultBranch", repository.defaultBranch())
            .query(Long.class).single();
    }

    /**
     * Serializes syncs and disconnection of one connection; must run inside their transaction. Returns whether the
     * connection has published a snapshot before, read under the lock. A connection disconnected meanwhile is not found.
     */
    public boolean lockForSync(long userId, long connectionId) {
        return db.sql("select last_synced_commit_sha is not null from repository_connections where id = :id and user_id = :userId for update")
            .param("id", connectionId).param("userId", userId).query(Boolean.class).optional()
            .orElseThrow(() -> new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Repository connection not found."));
    }

    /** Deletes the connection row; its documents and their reading and annotation rows must already be deleted. */
    void delete(long connectionId) {
        db.sql("delete from repository_connections where id = :id").param("id", connectionId).update();
    }

    public void markSynced(long connectionId, String commitSha, Instant syncedAt) {
        db.sql("update repository_connections set last_synced_commit_sha = :commitSha, last_synced_at = :syncedAt where id = :id")
            .param("commitSha", commitSha).param("syncedAt", java.sql.Timestamp.from(syncedAt)).param("id", connectionId).update();
    }

    private static Connection connection(ResultSet row, int n) throws SQLException {
        var synced = row.getTimestamp(8);
        return new Connection(row.getLong(1), row.getLong(2), row.getLong(3), row.getString(4), row.getString(5),
            row.getString(6), row.getString(7), synced == null ? null : synced.toInstant(), row.getInt(9));
    }
}
