package com.reporead.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the documents table: logical Markdown files of a connection. A path keeps its document; a document whose exact
 * content reappears at exactly one new path keeps its identity there (a move).
 */
@Component
public class Documents {
    private final JdbcClient db;

    public Documents(JdbcClient db) {
        this.db = db;
    }

    public record SourceFile(String path, String blobSha) {}
    public record Summary(long id, String path, String title, String blobSha) {}
    /** A document that kept its identity under a new path. */
    public record Move(long documentId, String fromPath, String toPath) {}
    record Existing(long id, String path, String blobSha, boolean deleted) {}
    public record Located(long id, String path, String title, String blobSha, String commitSha, boolean deleted,
                          String owner, String repositoryName) {}

    /**
     * Applies one complete tree snapshot. Moved documents take their new path first; then paths in the snapshot are
     * inserted or updated (and undeleted), and paths absent from it are marked deleted, never removed. Callers must hold
     * the connection's sync lock inside a transaction. Returns the moves applied.
     */
    public List<Move> publishSnapshot(long connectionId, String commitSha, List<SourceFile> files, Instant syncedAt) {
        var existing = db.sql("select id, path, current_blob_sha, deleted_at is not null from documents where repository_connection_id = :connectionId")
            .param("connectionId", connectionId)
            .query((row, n) -> new Existing(row.getLong(1), row.getString(2), row.getString(3), row.getBoolean(4))).list();
        var moves = exactMoves(existing, files);
        for (var move : moves) {
            db.sql("update documents set path = :path, title = :title where id = :id")
                .param("path", move.toPath()).param("title", title(move.toPath())).param("id", move.documentId()).update();
        }
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
        return moves;
    }

    /**
     * Pairs an active document whose path left the snapshot with a path that has never had a document, when they have
     * the same blob SHA and that SHA is unique on both sides. Identical-content duplicates are never merged; a path that
     * once had a document resumes that document instead.
     */
    static List<Move> exactMoves(List<Existing> existing, List<SourceFile> files) {
        var snapshotPaths = new HashSet<String>();
        for (var file : files) snapshotPaths.add(file.path());
        var knownPaths = new HashSet<String>();
        var vanished = new HashMap<String, List<Existing>>();
        for (var row : existing) {
            knownPaths.add(row.path());
            if (!row.deleted() && !snapshotPaths.contains(row.path())) vanished.computeIfAbsent(row.blobSha(), sha -> new ArrayList<>()).add(row);
        }
        Map<String, List<SourceFile>> fresh = new HashMap<>();
        for (var file : files) {
            if (!knownPaths.contains(file.path())) fresh.computeIfAbsent(file.blobSha(), sha -> new ArrayList<>()).add(file);
        }
        var moves = new ArrayList<Move>();
        for (var entry : vanished.entrySet()) {
            var targets = fresh.getOrDefault(entry.getKey(), List.of());
            if (entry.getValue().size() == 1 && targets.size() == 1) {
                moves.add(new Move(entry.getValue().getFirst().id(), entry.getValue().getFirst().path(), targets.getFirst().path()));
            }
        }
        return moves;
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
