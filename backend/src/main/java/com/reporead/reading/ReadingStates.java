package com.reporead.reading;

import com.reporead.repository.RepositoryConnections;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Owns reading_states. Reading progress is last-write-wins by the client's lastReadAt. */
@Component
public class ReadingStates {
    private final JdbcClient db;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final RepositoryConnections connections;

    public ReadingStates(
            JdbcClient db, JsonMapper json, TransactionTemplate transaction, RepositoryConnections connections) {
        this.db = db;
        this.json = json;
        this.transaction = transaction;
        this.connections = connections;
    }

    /** A semantic location: restored by heading path, then text prefix, then block index, then percent. */
    public record Anchor(List<String> headingPath, @Nullable String textPrefix, Integer blockIndex) {}

    /** The note's own title, path, version and removal are in its document list, their one source for clients. */
    public record State(
            long documentId, String lastReadBlobSha, int progressPercent, Anchor anchor, Instant lastReadAt) {}

    private static final String SELECT = """
        select r.document_id, r.last_read_blob_sha, r.progress_percent, r.anchor_json::text as anchor_json, r.last_read_at
        from reading_states r""";

    /**
     * Applies the write only if it is newer than the stored one; returns the state that is now current. Holds the
     * document's connection lock while it writes, so a disconnect waits for it; a document that is not this user's, or
     * was disconnected meanwhile, is 404.
     */
    State save(
            long userId,
            long documentId,
            String lastReadBlobSha,
            int progressPercent,
            Anchor anchor,
            Instant lastReadAt) {
        return transaction.execute(status -> {
            connections.lockForDocumentWrite(userId, documentId);
            db.sql("""
                    insert into reading_states (user_id, document_id, last_read_blob_sha, progress_percent, anchor_json, last_read_at)
                    values (:userId, :documentId, :sha, :progress, cast(:anchor as jsonb), :lastReadAt)
                    on conflict (user_id, document_id) do update
                    set last_read_blob_sha = excluded.last_read_blob_sha, progress_percent = excluded.progress_percent,
                        anchor_json = excluded.anchor_json, last_read_at = excluded.last_read_at
                    where reading_states.last_read_at < excluded.last_read_at""")
                    .param("userId", userId)
                    .param("documentId", documentId)
                    .param("sha", lastReadBlobSha)
                    .param("progress", progressPercent)
                    .param("anchor", json.writeValueAsString(anchor))
                    .param("lastReadAt", Timestamp.from(lastReadAt))
                    .update();
            return db.sql(SELECT + " where r.user_id = :userId and r.document_id = :documentId")
                    .param("userId", userId)
                    .param("documentId", documentId)
                    .query(this::state)
                    .single();
        });
    }

    /** Deletes reading progress on a connection's documents; returns how many states were deleted. */
    public int deleteOnConnection(long connectionId) {
        return db.sql(
                        "delete from reading_states where document_id in (select id from documents where repository_connection_id = :connectionId)")
                .param("connectionId", connectionId)
                .update();
    }

    /** Most recently read first. Bounded by the user's documents. */
    public List<State> list(long userId) {
        return db.sql(SELECT + " where r.user_id = :userId order by r.last_read_at desc")
                .param("userId", userId)
                .query(this::state)
                .list();
    }

    private State state(ResultSet row, int n) throws SQLException {
        return new State(
                row.getLong("document_id"),
                row.getString("last_read_blob_sha"),
                row.getInt("progress_percent"),
                json.readValue(row.getString("anchor_json"), Anchor.class),
                row.getTimestamp("last_read_at").toInstant());
    }
}
