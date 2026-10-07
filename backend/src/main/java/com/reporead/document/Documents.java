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
import java.util.Set;

/**
 * Owns the documents table: logical Markdown files of a connection. A path keeps its document; a document whose exact
 * content reappears at exactly one new path keeps its identity there (a move), as does one the sync recognized as moved
 * and edited (a content move).
 */
@Component
public class Documents {
    private final JdbcClient db;

    public Documents(JdbcClient db) {
        this.db = db;
    }

    public record SourceFile(String path, String blobSha) {}
    /** [contentChangedAt]: when a refresh found the note new, changed, or back; null if RepoRead never saw it change. */
    public record Summary(long id, String path, String title, String blobSha, Instant contentChangedAt) {}
    /** A document that kept its identity under a new path. */
    public record Move(long documentId, String fromPath, String toPath) {}
    public record Existing(long id, String path, String blobSha, boolean deleted) {}
    public record Located(long id, String path, String title, String blobSha, String commitSha, boolean deleted,
                          String owner, String repositoryName) {}

    /**
     * Applies one complete tree snapshot. Moved documents take their new path first; then paths in the snapshot are
     * inserted or updated (and undeleted), and paths absent from it are marked deleted, never removed. [contentMoves]
     * were decided before the lock and apply only if still valid against the locked rows. A note that is new, has a new
     * blob, or is back after being deleted records [syncedAt] as its content change, except in a connection's first
     * snapshot ([previouslySynced] false), where RepoRead has seen nothing change. Callers must hold the connection's sync
     * lock inside a transaction. Returns the moves applied.
     */
    public List<Move> publishSnapshot(long connectionId, String commitSha, List<SourceFile> files, Instant syncedAt, List<Move> contentMoves,
                                      boolean previouslySynced) {
        var existing = existing(connectionId);
        var moves = new ArrayList<>(exactMoves(existing, files));
        moves.addAll(stillMoves(contentMoves, existing, files, moves));
        for (var move : moves) {
            db.sql("update documents set path = :path, title = :title where id = :id")
                .param("path", move.toPath()).param("title", title(move.toPath())).param("id", move.documentId()).update();
        }
        String[] paths = files.stream().map(SourceFile::path).toArray(String[]::new);
        String[] titles = files.stream().map(file -> title(file.path())).toArray(String[]::new);
        String[] blobs = files.stream().map(SourceFile::blobSha).toArray(String[]::new);
        var now = Timestamp.from(syncedAt);
        db.sql("""
                insert into documents (repository_connection_id, path, title, current_blob_sha, current_commit_sha, last_synced_at,
                                       content_changed_at)
                select :connectionId, source.path, source.title, source.blob, :commitSha, :now, cast(:firstSeenChange as timestamptz)
                from unnest(:paths::text[], :titles::text[], :blobs::text[]) as source (path, title, blob)
                on conflict (repository_connection_id, path) do update
                set title = excluded.title, current_blob_sha = excluded.current_blob_sha,
                    current_commit_sha = excluded.current_commit_sha, last_synced_at = excluded.last_synced_at, deleted_at = null,
                    content_changed_at = case when documents.current_blob_sha <> excluded.current_blob_sha or documents.deleted_at is not null
                                              then excluded.last_synced_at else documents.content_changed_at end""")
            .param("connectionId", connectionId).param("commitSha", commitSha).param("now", now).param("firstSeenChange", previouslySynced ? now : null)
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
    public static List<Move> exactMoves(List<Existing> existing, List<SourceFile> files) {
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

    /**
     * A content move still applies if its document is active at the path it left, that path is not in the snapshot, its
     * target is in the snapshot and has never had a document, and no exact move claimed either side.
     */
    private static List<Move> stillMoves(List<Move> contentMoves, List<Existing> existing, List<SourceFile> files, List<Move> exact) {
        var snapshotPaths = new HashSet<String>();
        for (var file : files) snapshotPaths.add(file.path());
        var knownPaths = new HashSet<String>();
        for (var row : existing) knownPaths.add(row.path());
        var claimedIds = new HashSet<Long>();
        var claimedPaths = new HashSet<String>();
        for (var move : exact) {
            claimedIds.add(move.documentId());
            claimedPaths.add(move.toPath());
        }
        return contentMoves.stream().filter(move -> existing.stream().anyMatch(row -> row.id() == move.documentId() && !row.deleted()
                && row.path().equals(move.fromPath()))
            && !snapshotPaths.contains(move.fromPath()) && snapshotPaths.contains(move.toPath()) && !knownPaths.contains(move.toPath())
            && !claimedIds.contains(move.documentId()) && !claimedPaths.contains(move.toPath())).toList();
    }

    public List<Existing> existing(long connectionId) {
        return db.sql("select id, path, current_blob_sha, deleted_at is not null from documents where repository_connection_id = :connectionId")
            .param("connectionId", connectionId)
            .query((row, n) -> new Existing(row.getLong(1), row.getString(2), row.getString(3), row.getBoolean(4))).list();
    }

    /**
     * Documents of a connection that carry the user's own data: reading progress, a bookmark, or a highlight. Only their
     * identity is worth recognizing a content move for; a read-only projection of the reading and annotation tables.
     */
    public Set<Long> withUserState(long connectionId) {
        return new HashSet<>(db.sql("""
                select d.id from documents d where d.repository_connection_id = :connectionId
                and (exists (select 1 from reading_states r where r.document_id = d.id)
                     or exists (select 1 from annotations a where a.document_id = d.id))""")
            .param("connectionId", connectionId).query(Long.class).list());
    }

    public List<Summary> list(long connectionId) {
        return db.sql("""
                select id, path, title, current_blob_sha, content_changed_at from documents
                where repository_connection_id = :connectionId and deleted_at is null order by path""")
            .param("connectionId", connectionId)
            .query((row, n) -> {
                var changedAt = row.getTimestamp(5);
                return new Summary(row.getLong(1), row.getString(2), row.getString(3), row.getString(4),
                    changedAt == null ? null : changedAt.toInstant());
            }).list();
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
