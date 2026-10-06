package com.reporead.reading;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Owns reading_states. Reading progress is last-write-wins by the client's lastReadAt. */
@Component
public class ReadingStates {
    private final JdbcClient db;
    private final JsonMapper json;

    public ReadingStates(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    /** A semantic location: restored by heading path, then text prefix, then block index, then percent. */
    public record Anchor(List<String> headingPath, String textPrefix, Integer blockIndex) {}

    public record State(long documentId, long repositoryId, String path, String title, String currentBlobSha, boolean deleted,
                        String lastReadBlobSha, int progressPercent, Anchor anchor, Instant lastReadAt) {}

    private static final String SELECT = """
        select r.document_id, d.repository_connection_id, d.path, d.title, d.current_blob_sha, d.deleted_at is not null,
               r.last_read_blob_sha, r.progress_percent, r.anchor_json::text, r.last_read_at
        from reading_states r join documents d on d.id = r.document_id""";

    /** Applies the write only if it is newer than the stored one; returns the state that is now current. */
    State save(long userId, long documentId, String lastReadBlobSha, int progressPercent, Anchor anchor, Instant lastReadAt) {
        db.sql("""
                insert into reading_states (user_id, document_id, last_read_blob_sha, progress_percent, anchor_json, last_read_at)
                values (:userId, :documentId, :sha, :progress, cast(:anchor as jsonb), :lastReadAt)
                on conflict (user_id, document_id) do update
                set last_read_blob_sha = excluded.last_read_blob_sha, progress_percent = excluded.progress_percent,
                    anchor_json = excluded.anchor_json, last_read_at = excluded.last_read_at
                where reading_states.last_read_at < excluded.last_read_at""")
            .param("userId", userId).param("documentId", documentId).param("sha", lastReadBlobSha)
            .param("progress", progressPercent).param("anchor", json.writeValueAsString(anchor))
            .param("lastReadAt", Timestamp.from(lastReadAt)).update();
        return db.sql(SELECT + " where r.user_id = :userId and r.document_id = :documentId")
            .param("userId", userId).param("documentId", documentId).query(this::state).single();
    }

    /** Most recently read first. Bounded by the user's documents. */
    List<State> list(long userId) {
        return db.sql(SELECT + " where r.user_id = :userId order by r.last_read_at desc")
            .param("userId", userId).query(this::state).list();
    }

    private State state(ResultSet row, int n) throws SQLException {
        return new State(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4), row.getString(5), row.getBoolean(6),
            row.getString(7), row.getInt(8), json.readValue(row.getString(9), Anchor.class), row.getTimestamp(10).toInstant());
    }
}
