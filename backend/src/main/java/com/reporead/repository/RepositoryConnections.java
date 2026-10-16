package com.reporead.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.auth.AppSessions;
import com.reporead.github.GitHubApi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns repository_connections. Every read is scoped to the requesting user. */
@Component
public class RepositoryConnections {
    private final JdbcClient db;
    private final TransactionTemplate transaction;
    private final AppSessions sessions;

    public RepositoryConnections(JdbcClient db, TransactionTemplate transaction, AppSessions sessions) {
        this.db = db;
        this.transaction = transaction;
        this.sessions = sessions;
    }

    public record Connection(
            long id,
            long githubRepositoryId,
            String owner,
            String name,
            String defaultBranch,
            String lastSyncedCommitSha,
            int documentCount) {
        @JsonProperty("fullName")
        public String fullName() {
            return owner + "/" + name;
        }
    }

    private static final String SELECT = """
        select c.id, c.github_repository_id, c.owner, c.name, c.default_branch, c.last_synced_commit_sha,
               (select count(*) from documents d where d.repository_connection_id = c.id and d.deleted_at is null) as document_count
        from repository_connections c""";

    public List<Connection> list(long userId) {
        return db.sql(SELECT + " where c.user_id = :userId order by c.owner, c.name")
                .param("userId", userId)
                .query(Connection.class)
                .list();
    }

    public Optional<Connection> find(long userId, long connectionId) {
        return db.sql(SELECT + " where c.user_id = :userId and c.id = :id")
                .param("userId", userId)
                .param("id", connectionId)
                .query(Connection.class)
                .optional();
    }

    /** GitHub repository id → connection id, for marking which available repositories are already connected. */
    Map<Long, Long> connectedIds(long userId) {
        return db
                .sql("select github_repository_id, id from repository_connections where user_id = :userId")
                .param("userId", userId)
                .query((row, n) -> Map.entry(row.getLong("github_repository_id"), row.getLong("id")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Connecting twice returns the same connection, refreshed with GitHub's current metadata. Account deletion locks
     * the user before listing the connections it deletes, so under the user's lock this connection is either listed and
     * deleted with the rest, or refused because the account is already gone. It is read back under the same lock, so a
     * deletion cannot remove it before it is returned.
     */
    Connection connect(long userId, GitHubApi.Repository repository) {
        return transaction.execute(status -> {
            if (!sessions.lockUserForWrite(userId)) {
                throw new ApiFailure(Code.SIGN_IN_REQUIRED, "This RepoRead account was deleted; sign in again.");
            }
            long id = db.sql("""
                    insert into repository_connections (user_id, github_repository_id, owner, name, default_branch)
                    values (:userId, :githubRepositoryId, :owner, :name, :defaultBranch)
                    on conflict (user_id, github_repository_id) do update
                    set owner = excluded.owner, name = excluded.name, default_branch = excluded.default_branch
                    returning id""")
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

    /** The commit a connection last published (null before its first sync), read under its lock. */
    public record Checkpoint(String commitSha) {}

    /**
     * Serializes syncs and disconnection of one connection; must run inside their transaction. Returns the published
     * checkpoint, read under the lock. A connection disconnected meanwhile is not found.
     */
    public Checkpoint lockForSync(long userId, long connectionId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("lockForSync(connectionId=" + connectionId
                    + ") ran outside a transaction, where its row lock is released as soon as it is taken");
        }
        return db.sql(
                        "select last_synced_commit_sha as commit_sha from repository_connections where id = :id and user_id = :userId for update")
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
                select c.id from repository_connections c join documents d on d.repository_connection_id = c.id
                where d.id = :documentId and c.user_id = :userId for share of c""")
                .param("documentId", documentId)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElseThrow(ApiFailure::notFound);
    }

    /** Deletes the connection row; its documents and their reading and annotation rows must already be deleted. */
    public void delete(long connectionId) {
        db.sql("delete from repository_connections where id = :id")
                .param("id", connectionId)
                .update();
    }

    public void markSynced(long connectionId, String commitSha) {
        db.sql("update repository_connections set last_synced_commit_sha = :commitSha where id = :id")
                .param("commitSha", commitSha)
                .param("id", connectionId)
                .update();
    }
}
