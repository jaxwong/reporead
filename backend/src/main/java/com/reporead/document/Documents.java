package com.reporead.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Owns the documents table: logical Markdown files of a connection, identified by path in Stage 1. */
@Component
public class Documents {
    private final JdbcClient db;

    public Documents(JdbcClient db) {
        this.db = db;
    }

    public record SourceFile(String path, String blobSha) {}
    public record Summary(long id, String path, String title, String blobSha) {}
    public record Located(long id, String path, String title, String blobSha, String commitSha, boolean deleted,
                          String owner, String repositoryName) {}

    /**
     * Applies one complete tree snapshot. Paths in the snapshot are inserted or updated (and undeleted); paths absent
     * from it are marked deleted, never removed. Callers must hold the connection's sync lock inside a transaction.
     */
    public void publishSnapshot(long connectionId, String commitSha, List<SourceFile> files, Instant syncedAt) {
        String[] paths = files.stream().map(SourceFile::path).toArray(String[]::new);
        String[] titles = files.stream().map(file -> title(file.path())).toArray(String[]::new);
        String[] blobs = files.stream().map(SourceFile::blobSha).toArray(String[]::new);
        var now = Timestamp.from(syncedAt);
        db.sql("""
                insert into documents (repository_connection_id, path, title, current_blob_sha, current_commit_sha, last_synced_at)
                select :connectionId, source.path, source.title, source.blob, :commitSha, :now
                from unnest(:paths::text[], :titles::text[], :blobs::text[]) as source (path, title, blob)
                on conflict (repository_connection_id, path) do update
                set title = excluded.title, current_blob_sha = excluded.current_blob_sha,
                    current_commit_sha = excluded.current_commit_sha, last_synced_at = excluded.last_synced_at, deleted_at = null""")
            .param("connectionId", connectionId).param("commitSha", commitSha).param("now", now)
            .param("paths", paths).param("titles", titles).param("blobs", blobs).update();
        db.sql("""
                update documents set deleted_at = :now
                where repository_connection_id = :connectionId and deleted_at is null and not (path = any(:paths::text[]))""")
            .param("now", now).param("connectionId", connectionId).param("paths", paths).update();
    }

    public List<Summary> list(long connectionId) {
        return db.sql("""
                select id, path, title, current_blob_sha from documents
                where repository_connection_id = :connectionId and deleted_at is null order by path""")
            .param("connectionId", connectionId)
            .query((row, n) -> new Summary(row.getLong(1), row.getString(2), row.getString(3), row.getString(4))).list();
    }

    /** Finds a document only if it belongs to one of this user's connections. */
    public Optional<Located> find(long userId, long documentId) {
        return db.sql("""
                select d.id, d.path, d.title, d.current_blob_sha, d.current_commit_sha, d.deleted_at is not null, c.owner, c.name
                from documents d join repository_connections c on c.id = d.repository_connection_id
                where d.id = :id and c.user_id = :userId""")
            .param("id", documentId).param("userId", userId)
            .query((row, n) -> new Located(row.getLong(1), row.getString(2), row.getString(3), row.getString(4),
                row.getString(5), row.getBoolean(6), row.getString(7), row.getString(8))).optional();
    }

    static String title(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.substring(0, file.length() - ".md".length());
    }
}
