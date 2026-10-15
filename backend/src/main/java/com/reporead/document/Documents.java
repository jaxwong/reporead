package com.reporead.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    /** [commitSha]: the connection's last published commit, which the document is at unless [deleted]. */
    public record Located(long id, String path, String title, String blobSha, String commitSha, boolean deleted,
                          String owner, String repositoryName, long connectionId) {}

    /**
     * Applies one complete tree snapshot. Moved documents take their new path first; then paths in the snapshot are
     * inserted, or updated when their content changed or they were deleted, and paths absent from it are marked deleted,
     * never removed. An unchanged document's row is not rewritten. [contentMoves] were decided before the lock and apply
     * only if both their sides are still unmatched in the locked rows. A note that is new, has a new blob, or is back
     * after being deleted records [syncedAt] as its content change, except in a connection's first snapshot
     * ([previouslySynced] false), where RepoRead has seen nothing change. Callers must hold the connection's sync lock
     * inside a transaction. Returns the moves applied.
     */
    public List<Move> publishSnapshot(long connectionId, List<SourceFile> files, Instant syncedAt, List<Move> contentMoves,
                                      boolean previouslySynced) {
        var unmatched = unmatched(existing(connectionId), files);
        var moves = new ArrayList<>(unmatched.exact());
        for (var move : contentMoves) {
            boolean documentStillVanished = unmatched.vanished().stream()
                .anyMatch(row -> row.id() == move.documentId() && row.path().equals(move.fromPath()));
            boolean targetStillFresh = unmatched.fresh().stream().anyMatch(file -> file.path().equals(move.toPath()));
            if (documentStillVanished && targetStillFresh) moves.add(move);
        }
        for (var move : moves) {
            db.sql("update documents set path = :path, title = :title where id = :id")
                .param("path", move.toPath()).param("title", title(move.toPath())).param("id", move.documentId()).update();
        }
        String[] paths = files.stream().map(SourceFile::path).toArray(String[]::new);
        String[] titles = files.stream().map(file -> title(file.path())).toArray(String[]::new);
        String[] blobs = files.stream().map(SourceFile::blobSha).toArray(String[]::new);
        var now = Timestamp.from(syncedAt);
        db.sql("""
                insert into documents (repository_connection_id, path, title, current_blob_sha, content_changed_at)
                select :connectionId, source.path, source.title, source.blob, cast(:firstSeenChange as timestamptz)
                from unnest(:paths::text[], :titles::text[], :blobs::text[]) as source (path, title, blob)
                on conflict (repository_connection_id, path) do update
                set current_blob_sha = excluded.current_blob_sha, deleted_at = null, content_changed_at = :now
                where documents.current_blob_sha <> excluded.current_blob_sha or documents.deleted_at is not null""")
            .param("connectionId", connectionId).param("now", now).param("firstSeenChange", previouslySynced ? now : null)
            .param("paths", paths).param("titles", titles).param("blobs", blobs).update();
        db.sql("""
                update documents set deleted_at = :now
                where repository_connection_id = :connectionId and deleted_at is null and not (path = any(:paths::text[]))""")
            .param("now", now).param("connectionId", connectionId).param("paths", paths).update();
        return moves;
    }

    /**
     * A snapshot against the stored documents. [exact] pairs an active document whose path left the snapshot with a path
     * that has never had a document, when they have the same blob SHA and that SHA is unique on both sides:
     * identical-content duplicates are never merged, and a path that once had a document resumes that document instead.
     * [vanished] and [fresh] are the sides left unpaired, the only candidates for a content move.
     */
    public record Unmatched(List<Move> exact, List<Existing> vanished, List<SourceFile> fresh) {}

    public static Unmatched unmatched(List<Existing> existing, List<SourceFile> files) {
        var snapshotPaths = files.stream().map(SourceFile::path).collect(Collectors.toSet());
        var knownPaths = existing.stream().map(Existing::path).collect(Collectors.toSet());
        var vanished = existing.stream().filter(row -> !row.deleted() && !snapshotPaths.contains(row.path())).toList();
        var fresh = files.stream().filter(file -> !knownPaths.contains(file.path())).toList();
        var vanishedByBlob = vanished.stream().collect(Collectors.groupingBy(Existing::blobSha));
        var freshByBlob = fresh.stream().collect(Collectors.groupingBy(SourceFile::blobSha));
        var exact = new ArrayList<Move>();
        for (var entry : vanishedByBlob.entrySet()) {
            var documents = entry.getValue();
            var targets = freshByBlob.getOrDefault(entry.getKey(), List.of());
            if (documents.size() == 1 && targets.size() == 1) {
                exact.add(new Move(documents.getFirst().id(), documents.getFirst().path(), targets.getFirst().path()));
            }
        }
        var movedIds = exact.stream().map(Move::documentId).collect(Collectors.toSet());
        var movedTo = exact.stream().map(Move::toPath).collect(Collectors.toSet());
        var unpairedVanished = vanished.stream().filter(row -> !movedIds.contains(row.id())).toList();
        var unpairedFresh = fresh.stream().filter(file -> !movedTo.contains(file.path())).toList();
        return new Unmatched(exact, unpairedVanished, unpairedFresh);
    }

    /** Deletes a connection's documents, active and deleted upstream; returns their ids. Reading and annotation rows must go first. */
    public List<Long> deleteConnection(long connectionId) {
        return db.sql("delete from documents where repository_connection_id = :connectionId returning id")
            .param("connectionId", connectionId).query(Long.class).list().stream().sorted().toList();
    }

    public List<Existing> existing(long connectionId) {
        return db.sql("""
                select id, path, current_blob_sha, deleted_at is not null as deleted from documents
                where repository_connection_id = :connectionId""")
            .param("connectionId", connectionId)
            .query((row, n) -> new Existing(row.getLong("id"), row.getString("path"), row.getString("current_blob_sha"),
                row.getBoolean("deleted"))).list();
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
                var changedAt = row.getTimestamp("content_changed_at");
                return new Summary(row.getLong("id"), row.getString("path"), row.getString("title"), row.getString("current_blob_sha"),
                    changedAt == null ? null : changedAt.toInstant());
            }).list();
    }

    /** Finds a document only if it belongs to one of this user's connections. */
    public Optional<Located> find(long userId, long documentId) {
        return db.sql("""
                select d.id, d.path, d.title, d.current_blob_sha, c.last_synced_commit_sha, d.deleted_at is not null as deleted, c.owner,
                       c.name as repository_name, c.id as connection_id
                from documents d join repository_connections c on c.id = d.repository_connection_id
                where d.id = :id and c.user_id = :userId""")
            .param("id", documentId).param("userId", userId)
            .query((row, n) -> new Located(row.getLong("id"), row.getString("path"), row.getString("title"),
                row.getString("current_blob_sha"), row.getString("last_synced_commit_sha"), row.getBoolean("deleted"), row.getString("owner"),
                row.getString("repository_name"), row.getLong("connection_id"))).optional();
    }

    static String title(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.substring(0, file.length() - ".md".length());
    }
}
