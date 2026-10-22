package com.reporead.document;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

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
    /**
     * {@code contentChangedAt}: when a refresh found the note new, changed, or back; null if RepoRead never saw it
     * change. {@code deleted}: the note was not in the latest complete refresh; it is listed because the user's reading
     * state, bookmark or highlights on it are kept, and this is the one place a client learns its title and path.
     */
    public record Summary(
            long id,
            String path,
            String title,
            String blobSha,
            @Nullable Instant contentChangedAt,
            boolean deleted) {}
    /** A document that kept its identity under a new path. */
    public record Move(long documentId, String fromPath, String toPath) {}

    public record Existing(long id, String path, String blobSha, boolean deleted) {}
    /** {@code commitSha}: the connection's last published commit, which the document is at unless {@code deleted}. */
    public record Located(
            long id,
            String path,
            String title,
            String blobSha,
            String commitSha,
            boolean deleted,
            String owner,
            String repositoryName,
            long connectionId) {}

    /**
     * Applies one complete tree snapshot. Moved documents take their new path first; then paths in the snapshot are
     * inserted, or updated when their content changed or they were deleted, and paths absent from it are marked
     * deleted, never removed. An unchanged document's row is not rewritten. {@code contentMoves} were decided before
     * the lock and apply only if both their sides are still unmatched in the locked rows. A note that is new, has a new
     * blob, or is back after being deleted records {@code syncedAt} as its content change, except in a connection's
     * first snapshot ({@code previouslySynced} false), where RepoRead has seen nothing change. Callers must hold the
     * connection's sync lock inside a transaction. Returns the moves applied.
     */
    public List<Move> publishSnapshot(
            long connectionId,
            List<SourceFile> snapshotFiles,
            Instant syncedAt,
            List<Move> contentMoves,
            boolean previouslySynced) {
        var unmatched = unmatched(existing(connectionId), snapshotFiles);
        var moves = new ArrayList<>(unmatched.exact());
        for (var move : contentMoves) {
            boolean documentStillVanished = unmatched.vanished().stream()
                    .anyMatch(row -> row.id() == move.documentId() && row.path().equals(move.fromPath()));
            boolean targetStillFresh =
                    unmatched.fresh().stream().anyMatch(file -> file.path().equals(move.toPath()));
            if (documentStillVanished && targetStillFresh) moves.add(move);
        }
        // One statement for every move, as for the paths below; a move's target is a path no document holds.
        db.sql("""
                UPDATE documents SET path = move.path, title = move.title
                FROM unnest(:ids::bigint[], :paths::text[], :titles::text[]) AS move (id, path, title)
                WHERE documents.id = move.id""")
                .param("ids", moves.stream().mapToLong(Move::documentId).boxed().toArray(Long[]::new))
                .param("paths", moves.stream().map(Move::toPath).toArray(String[]::new))
                .param(
                        "titles",
                        moves.stream().map(move -> title(move.toPath())).toArray(String[]::new))
                .update();
        String[] paths = snapshotFiles.stream().map(SourceFile::path).toArray(String[]::new);
        String[] titles = snapshotFiles.stream().map(file -> title(file.path())).toArray(String[]::new);
        String[] blobs = snapshotFiles.stream().map(SourceFile::blobSha).toArray(String[]::new);
        var now = Timestamp.from(syncedAt);
        db.sql("""
                INSERT INTO documents (repository_connection_id, path, title, current_blob_sha, content_changed_at)
                SELECT :connectionId, source.path, source.title, source.blob, CAST(:firstSeenChange AS timestamptz)
                FROM unnest(:paths::text[], :titles::text[], :blobs::text[]) AS source (path, title, blob)
                ON CONFLICT (repository_connection_id, path) DO UPDATE
                SET current_blob_sha = excluded.current_blob_sha, deleted_at = NULL, content_changed_at = :now
                WHERE documents.current_blob_sha <> excluded.current_blob_sha OR documents.deleted_at IS NOT NULL""")
                .param("connectionId", connectionId)
                .param("now", now)
                .param("firstSeenChange", previouslySynced ? now : null)
                .param("paths", paths)
                .param("titles", titles)
                .param("blobs", blobs)
                .update();
        db.sql("""
                UPDATE documents SET deleted_at = :now
                WHERE repository_connection_id = :connectionId AND deleted_at IS NULL AND NOT (path = any(:paths::text[]))""")
                .param("now", now)
                .param("connectionId", connectionId)
                .param("paths", paths)
                .update();
        return moves;
    }

    /**
     * A snapshot against the stored documents. {@code exact} pairs an active document whose path left the snapshot with
     * a path that has never had a document, when they have the same blob SHA and no other active document or snapshot
     * path has it: content another note shares (identical duplicates, untouched copies of a template) identifies
     * neither, and a path that once had a document resumes that document instead. {@code vanished} and {@code fresh}
     * are the sides left unpaired, the only candidates for a content move.
     */
    public record Unmatched(List<Move> exact, List<Existing> vanished, List<SourceFile> fresh) {}

    public static Unmatched unmatched(List<Existing> existingDocuments, List<SourceFile> snapshotFiles) {
        var snapshotPaths = snapshotFiles.stream().map(SourceFile::path).collect(Collectors.toSet());
        var knownPaths = existingDocuments.stream().map(Existing::path).collect(Collectors.toSet());
        var vanished = existingDocuments.stream()
                .filter(row -> !row.deleted() && !snapshotPaths.contains(row.path()))
                .toList();
        var freshSnapshotFiles = snapshotFiles.stream()
                .filter(file -> !knownPaths.contains(file.path()))
                .toList();
        var activeByBlob = existingDocuments.stream()
                .filter(row -> !row.deleted())
                .collect(Collectors.groupingBy(Existing::blobSha));
        var snapshotFilesByBlob = snapshotFiles.stream().collect(Collectors.groupingBy(SourceFile::blobSha));
        var exact = new ArrayList<Move>();
        for (var document : vanished) {
            var targets = snapshotFilesByBlob.get(document.blobSha());
            if (activeByBlob.get(document.blobSha()).size() == 1
                    && targets != null
                    && targets.size() == 1
                    && !knownPaths.contains(targets.getFirst().path())) {
                exact.add(new Move(
                        document.id(), document.path(), targets.getFirst().path()));
            }
        }
        var movedIds = exact.stream().map(Move::documentId).collect(Collectors.toSet());
        var movedTo = exact.stream().map(Move::toPath).collect(Collectors.toSet());
        var unpairedVanished =
                vanished.stream().filter(row -> !movedIds.contains(row.id())).toList();
        var unpairedFreshSnapshotFiles = freshSnapshotFiles.stream()
                .filter(file -> !movedTo.contains(file.path()))
                .toList();
        return new Unmatched(exact, unpairedVanished, unpairedFreshSnapshotFiles);
    }

    /**
     * Deletes a connection's documents, active and deleted upstream; returns their ids. Reading and annotation rows
     * must go first.
     */
    public List<Long> deleteConnection(long connectionId) {
        return db
                .sql("DELETE FROM documents WHERE repository_connection_id = :connectionId RETURNING id")
                .param("connectionId", connectionId)
                .query(Long.class)
                .list()
                .stream()
                .sorted()
                .toList();
    }

    public List<Existing> existing(long connectionId) {
        return db.sql("""
                SELECT id, path, current_blob_sha AS blob_sha, deleted_at IS NOT NULL AS deleted FROM documents
                WHERE repository_connection_id = :connectionId""")
                .param("connectionId", connectionId)
                .query(Existing.class)
                .list();
    }

    /**
     * Documents of a connection that carry the user's own data: reading progress, a bookmark, or a highlight. Only
     * their identity is worth recognizing a content move for; a read-only projection of the reading and annotation
     * tables.
     */
    public Set<Long> withUserState(long connectionId) {
        return new HashSet<>(db.sql("""
                SELECT d.id FROM documents d WHERE d.repository_connection_id = :connectionId
                AND (EXISTS (SELECT 1 FROM reading_states r WHERE r.document_id = d.id)
                     OR EXISTS (SELECT 1 FROM annotations a WHERE a.document_id = d.id))""")
                .param("connectionId", connectionId)
                .query(Long.class)
                .list());
    }

    /**
     * Every document of every connection of this user, deleted ones too, by connection id, each connection's ordered by
     * path. One query; a connection without documents has no entry.
     */
    public Map<Long, List<Summary>> listForUser(long userId) {
        return db
                .sql("""
                        SELECT d.repository_connection_id, d.id, d.path, d.title, d.current_blob_sha, d.content_changed_at,
                               d.deleted_at IS NOT NULL AS deleted
                        FROM documents d JOIN repository_connections c ON c.id = d.repository_connection_id
                        WHERE c.user_id = :userId ORDER BY d.path""")
                .param("userId", userId)
                .query((row, n) -> Map.entry(
                        row.getLong("repository_connection_id"),
                        new Summary(
                                row.getLong("id"),
                                row.getString("path"),
                                row.getString("title"),
                                row.getString("current_blob_sha"),
                                Optional.ofNullable(row.getTimestamp("content_changed_at"))
                                        .map(Timestamp::toInstant)
                                        .orElse(null),
                                row.getBoolean("deleted"))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    /** Finds a document only if it belongs to one of this user's connections. */
    public Optional<Located> find(long userId, long documentId) {
        return db.sql("""
                SELECT d.id, d.path, d.title, d.current_blob_sha AS blob_sha, c.last_synced_commit_sha AS commit_sha,
                       d.deleted_at IS NOT NULL AS deleted, c.owner, c.name AS repository_name, c.id AS connection_id
                FROM documents d JOIN repository_connections c ON c.id = d.repository_connection_id
                WHERE d.id = :id AND c.user_id = :userId""")
                .param("id", documentId)
                .param("userId", userId)
                .query(Located.class)
                .optional();
    }

    static String title(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.substring(0, file.length() - ".md".length());
    }
}
