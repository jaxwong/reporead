package com.reporead.annotation;

import com.fasterxml.jackson.annotation.JsonValue;
import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.repository.RepositoryConnections;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns HIGHLIGHT and CARD annotations: their original anchors, their current locations and status, and the idempotency
 * record of each client creation. Bookmarks are document-level state in their own table, owned by Bookmarks (ADR-17).
 */
@Component
public class Annotations {
    static final int MAX_PER_DOCUMENT = 500;
    static final int MAX_PER_USER = 5_000;

    private final JdbcClient db;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final RepositoryConnections connections;

    public Annotations(
            JdbcClient db, JsonMapper json, TransactionTemplate transaction, RepositoryConnections connections) {
        this.db = db;
        this.json = json;
        this.transaction = transaction;
        this.connections = connections;
    }

    public record DeletedAnnotations(int highlights, int cards) {}

    /** annotations.type; its check constraint (V7) also admits BOOKMARK until bookmarks' contract step (ADR-17). */
    public enum Type {
        HIGHLIGHT,
        CARD
    }

    /** What a highlight's or card's location means for its resolved version (see Annotation); constraint from V4. */
    public enum Status {
        ANCHORED,
        REANCHORED,
        ORPHANED
    }

    /**
     * Deletes every annotation on a connection's documents: highlights and cards (their anchors, locations, and review
     * logs cascade). Their mutation records stay, named by no annotation, so a late replay is answered as deleted.
     * Counted in SQL so a BOOKMARK row the previous release left (ADR-17's contract step) goes too, uncounted.
     */
    public DeletedAnnotations deleteOnConnection(long connectionId) {
        return db.sql("""
                WITH deleted AS (
                    DELETE FROM annotations WHERE document_id IN (SELECT id FROM documents WHERE repository_connection_id = :connectionId)
                    RETURNING type)
                SELECT count(*) FILTER (WHERE type = 'HIGHLIGHT') AS highlights, count(*) FILTER (WHERE type = 'CARD') AS cards
                FROM deleted""")
                .param("connectionId", connectionId)
                .query(DeletedAnnotations.class)
                .single();
    }

    /** Deletes a user's mutation records (request hashes); called when deleting the account, after its annotations. */
    public void deleteMutations(long userId) {
        db.sql("DELETE FROM annotation_mutations WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    /** A passage in one source version: a block's text from {@code startOffset} to {@code endOffset}, in context. */
    public record Passage(
            String sourceBlobSha,
            String blockId,
            String exactText,
            String prefixText,
            String suffixText,
            int startOffset,
            int endOffset,
            List<String> headingPath) {}

    /**
     * Where a highlight is now: a passage, and how distinguishable it was in that version, which re-anchoring weighs.
     * {@code blockSha} is the SHA-256 of its block's text when no other block has that text (else null),
     * {@code quoteOccurrences} how often its exact text occurs, {@code rivalContext} how similar the best other exact
     * occurrence's context is (0 when unique), and {@code rivalQuote} how similar the closest look-alike in another
     * block is (0 when none, 1 when there were too many to compare). Clients are sent only the passage.
     */
    public record Location(
            Passage passage, String blockSha, int quoteOccurrences, double rivalContext, double rivalQuote) {
        @JsonValue
        Passage json() {
            return passage;
        }
    }

    /**
     * {@code mutationId} is the client id that created it, so a client can match a pending local copy to it.
     * {@code anchor} is the original selection, never changed and never resolved from. {@code location} is the latest
     * trusted location, and {@code status} says what it means for version {@code resolvedBlobSha}: ANCHORED (the
     * original selection, in that version), REANCHORED (found again in that version, by the server or by the user), or
     * ORPHANED (not reliably found in that version; {@code location} is the last place the passage was known to be).
     * The note's title, path, current version and removal are in its document list.
     */
    public record Annotation(
            long id,
            String mutationId,
            long documentId,
            Type type,
            @Nullable String note,
            Status status,
            int version,
            Instant createdAt,
            Instant updatedAt,
            Passage anchor,
            Location location,
            String resolvedBlobSha,
            @Nullable String question) {}

    /** The result of a creation request, and whether it was the replay of an earlier identical request. */
    record Created(Annotation annotation, boolean replayed) {}

    private static final String SELECT = """
        SELECT a.id, a.document_id, a.type, a.note, a.status, a.version, a.created_at, a.updated_at, a.mutation_id, a.resolved_blob_sha,
               n.source_blob_sha AS anchor_source_blob_sha, n.block_id AS anchor_block_id, n.exact_text AS anchor_exact_text,
               n.prefix_text AS anchor_prefix_text, n.suffix_text AS anchor_suffix_text, n.start_offset AS anchor_start_offset,
               n.end_offset AS anchor_end_offset, n.heading_path::text AS anchor_heading_path,
               l.source_blob_sha AS location_source_blob_sha, l.block_id AS location_block_id, l.exact_text AS location_exact_text,
               l.prefix_text AS location_prefix_text, l.suffix_text AS location_suffix_text, l.start_offset AS location_start_offset,
               l.end_offset AS location_end_offset, l.heading_path::text AS location_heading_path,
               l.block_sha, l.quote_occurrences, l.rival_context, l.rival_quote, a.question
        FROM annotations a JOIN annotation_anchors n ON n.annotation_id = a.id
        JOIN annotation_locations l ON l.annotation_id = a.id
        WHERE a.type IN ('HIGHLIGHT', 'CARD') AND a.user_id = :userId""";

    List<Annotation> notebook(long userId) {
        return db.sql(SELECT + " ORDER BY a.created_at, a.id")
                .param("userId", userId)
                .query(this::annotation)
                .list();
    }

    List<Annotation> list(long userId, long documentId) {
        return db.sql(SELECT + " AND a.document_id = :documentId ORDER BY a.created_at, a.id")
                .param("userId", userId)
                .param("documentId", documentId)
                .query(this::annotation)
                .list();
    }

    Optional<Annotation> find(long userId, long id) {
        return db.sql(SELECT + " AND a.id = :id")
                .param("userId", userId)
                .param("id", id)
                .query(this::annotation)
                .optional();
    }

    /**
     * The earlier result for this mutation id, if any. Same request: the original annotation. Different content: 409.
     * The annotation was since deleted (its mutation record outlives it): 410, so a late replay cannot resurrect it.
     */
    Optional<Annotation> replay(long userId, UUID mutationId, byte[] requestHash) {
        var hash = db.sql(
                        "SELECT request_hash FROM annotation_mutations WHERE user_id = :userId AND mutation_id = :mutationId")
                .param("userId", userId)
                .param("mutationId", mutationId)
                .query((row, n) -> row.getBytes("request_hash"))
                .optional();
        if (hash.isEmpty()) return Optional.empty();
        if (!MessageDigest.isEqual(hash.get(), requestHash)) {
            throw new ApiFailure(
                    Code.MUTATION_ID_REUSED, "This mutationId was already used for a different annotation request.");
        }
        var created = db.sql(SELECT + " AND a.mutation_id = :mutationId")
                .param("userId", userId)
                .param("mutationId", mutationId)
                .query(this::annotation)
                .optional();
        if (created.isEmpty()) {
            throw new ApiFailure(
                    Code.ANNOTATION_DELETED, "The annotation created by this mutation has since been deleted.");
        }
        return created;
    }

    /**
     * Creates the annotation, its anchor (the passage of {@code location}, where it is first), and the mutation record
     * in one transaction. A concurrent request with the same mutation id waits on the primary key and then resolves as
     * a replay; a concurrent disconnect is waited for (404).
     */
    Created create(
            long userId,
            long documentId,
            UUID mutationId,
            byte[] requestHash,
            Location location,
            String note,
            Type type,
            String question) {
        var anchor = location.passage();
        return transaction.execute(status -> {
            // Serialize new-annotation admission across repositories. User before connection matches account deletion;
            // NO KEY UPDATE still permits the foreign-key checks of unrelated writes.
            db.sql("SELECT id FROM users WHERE id = :userId FOR NO KEY UPDATE")
                    .param("userId", userId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(ApiFailure::notFound);
            connections.lockForDocumentWrite(userId, documentId);
            Instant now = Instant.now();
            int claimed = db.sql("""
                    INSERT INTO annotation_mutations (user_id, mutation_id, request_hash, created_at)
                    VALUES (:userId, :mutationId, :hash, :now) ON CONFLICT DO NOTHING""")
                    .param("userId", userId)
                    .param("mutationId", mutationId)
                    .param("hash", requestHash)
                    .param("now", Timestamp.from(now))
                    .update();
            if (claimed == 0)
                return new Created(replay(userId, mutationId, requestHash).orElseThrow(), true);
            record Counts(long documentCount, long userCount) {}
            var counts = db.sql("""
                    SELECT count(*) FILTER (WHERE document_id = :documentId) AS document_count, count(*) AS user_count
                    FROM annotations WHERE user_id = :userId AND type IN ('HIGHLIGHT', 'CARD')""")
                    .param("documentId", documentId)
                    .param("userId", userId)
                    .query(Counts.class)
                    .single();
            if (counts.documentCount() >= MAX_PER_DOCUMENT || counts.userCount() >= MAX_PER_USER) {
                throw new ApiFailure(
                        Code.ANNOTATION_LIMIT,
                        "At most " + MAX_PER_DOCUMENT + " highlights and cards per document and " + MAX_PER_USER
                                + " per user may be stored; delete existing annotations before creating more.");
            }
            long id = db.sql("""
                    INSERT INTO annotations (user_id, document_id, type, note, created_at, updated_at, resolved_blob_sha, question,
                                             mutation_id)
                    VALUES (:userId, :documentId, :type, :note, :now, :now, :sha, :question, :mutationId) RETURNING id""")
                    .param("userId", userId)
                    .param("documentId", documentId)
                    .param("sha", anchor.sourceBlobSha())
                    .param("mutationId", mutationId)
                    .param("note", note)
                    .param("type", type.name())
                    .param("question", question)
                    .param("now", Timestamp.from(now))
                    .query(Long.class)
                    .single();
            db.sql("""
                    INSERT INTO annotation_anchors (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text,
                                                    start_offset, end_offset, heading_path)
                    VALUES (:id, :sha, :block, :exact, :prefix, :suffix, :start, :end, CAST(:headings AS jsonb))""")
                    .param("id", id)
                    .param("sha", anchor.sourceBlobSha())
                    .param("block", anchor.blockId())
                    .param("exact", anchor.exactText())
                    .param("prefix", anchor.prefixText())
                    .param("suffix", anchor.suffixText())
                    .param("start", anchor.startOffset())
                    .param("end", anchor.endOffset())
                    .param("headings", json.writeValueAsString(anchor.headingPath()))
                    .update();
            writeLocation(id, location);
            return new Created(find(userId, id).orElseThrow(), false);
        });
    }

    /** Optimistic update: applies only at {@code expectedVersion}; 409 if another edit landed first, 404 if absent. */
    Annotation updateNote(long userId, long id, int expectedVersion, String note) {
        return transaction.execute(tx -> {
            lockForWrite(userId, id);
            int updated = db.sql("""
                    UPDATE annotations SET note = :note, version = version + 1, updated_at = :now
                    WHERE id = :id AND user_id = :userId AND type IN ('HIGHLIGHT', 'CARD') AND version = :expected""")
                    .param("note", note)
                    .param("now", Timestamp.from(Instant.now()))
                    .param("id", id)
                    .param("userId", userId)
                    .param("expected", expectedVersion)
                    .update();
            if (updated == 0) throw missingOrConflict(userId, id);
            return find(userId, id).orElseThrow();
        });
    }

    void delete(long userId, long id, int expectedVersion) {
        int deleted = db.sql(
                        "DELETE FROM annotations WHERE id = :id AND user_id = :userId AND type IN ('HIGHLIGHT', 'CARD') AND version = :expected")
                .param("id", id)
                .param("userId", userId)
                .param("expected", expectedVersion)
                .update();
        if (deleted == 0) throw missingOrConflict(userId, id);
    }

    /**
     * Records resolving a highlight in version {@code blobSha}, only while it is still resolved against the version and
     * at the user-edit version {@code annotation} was read with, so a concurrent resolution or a reattachment wins.
     * Empty {@code found} orphans it and keeps its last location. The user-edit version is unchanged: re-anchoring is
     * not an edit. Returns whether this call applied.
     */
    boolean resolved(Annotation annotation, String blobSha, Optional<Location> found) {
        Status status = found.map(location ->
                        sameSelection(location.passage(), annotation.anchor()) ? Status.ANCHORED : Status.REANCHORED)
                .orElse(Status.ORPHANED);
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            int updated = db.sql("""
                    UPDATE annotations SET status = :status, resolved_blob_sha = :sha
                    WHERE id = :id AND type IN ('HIGHLIGHT', 'CARD') AND resolved_blob_sha = :expected AND version = :version""")
                    .param("status", status.name())
                    .param("sha", blobSha)
                    .param("id", annotation.id())
                    .param("expected", annotation.resolvedBlobSha())
                    .param("version", annotation.version())
                    .update();
            if (updated == 0) return false;
            found.ifPresent(location -> writeLocation(annotation.id(), location));
            return true;
        }));
    }

    /** The user's own placement: versioned like a note edit (409 if another change landed first, 404 if absent). */
    Annotation reattach(long userId, long id, int expectedVersion, Location location) {
        return transaction.execute(tx -> {
            lockForWrite(userId, id);
            int updated = db.sql("""
                    UPDATE annotations SET status = 'REANCHORED', resolved_blob_sha = :sha, version = version + 1, updated_at = :now
                    WHERE id = :id AND user_id = :userId AND type IN ('HIGHLIGHT', 'CARD') AND version = :expected""")
                    .param("sha", location.passage().sourceBlobSha())
                    .param("now", Timestamp.from(Instant.now()))
                    .param("id", id)
                    .param("userId", userId)
                    .param("expected", expectedVersion)
                    .update();
            if (updated == 0) throw missingOrConflict(userId, id);
            writeLocation(id, location);
            return find(userId, id).orElseThrow();
        });
    }

    /**
     * Locks the user's card {@code id} for a grade: its note's connection lock, as every write takes, then its row.
     * Runs inside that write's transaction; 404 if it is not this user's card.
     */
    void lockCard(long userId, long id) {
        lockForWrite(userId, id);
        db.sql("SELECT id FROM annotations WHERE id = :id AND user_id = :userId AND type = 'CARD' FOR UPDATE")
                .param("id", id)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElseThrow(ApiFailure::notFound);
    }

    /**
     * Holds off a disconnect of the repository that owns the user's highlight or card {@code id} until the calling
     * write commits, as every write to a note's rows does, so the write can read its result back. Runs inside that
     * write's transaction; 404 if the annotation is not this user's or was disconnected meanwhile.
     */
    private void lockForWrite(long userId, long id) {
        long documentId = db.sql(
                        "SELECT document_id FROM annotations WHERE id = :id AND user_id = :userId AND type IN ('HIGHLIGHT', 'CARD')")
                .param("id", id)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElseThrow(ApiFailure::notFound);
        connections.lockForDocumentWrite(userId, documentId);
    }

    private void writeLocation(long id, Location location) {
        var passage = location.passage();
        db.sql("""
                INSERT INTO annotation_locations (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text, start_offset,
                                                  end_offset, heading_path, block_sha, quote_occurrences, rival_context, rival_quote)
                VALUES (:id, :sha, :block, :exact, :prefix, :suffix, :start, :end, CAST(:headings AS jsonb), :blockSha, :occurrences,
                        :rivalContext, :rivalQuote)
                ON CONFLICT (annotation_id) DO UPDATE SET source_blob_sha = excluded.source_blob_sha, block_id = excluded.block_id,
                    exact_text = excluded.exact_text, prefix_text = excluded.prefix_text, suffix_text = excluded.suffix_text,
                    start_offset = excluded.start_offset, end_offset = excluded.end_offset, heading_path = excluded.heading_path,
                    block_sha = excluded.block_sha, quote_occurrences = excluded.quote_occurrences,
                    rival_context = excluded.rival_context, rival_quote = excluded.rival_quote""")
                .param("id", id)
                .param("sha", passage.sourceBlobSha())
                .param("block", passage.blockId())
                .param("exact", passage.exactText())
                .param("prefix", passage.prefixText())
                .param("suffix", passage.suffixText())
                .param("start", passage.startOffset())
                .param("end", passage.endOffset())
                .param("headings", json.writeValueAsString(passage.headingPath()))
                .param("blockSha", location.blockSha())
                .param("occurrences", location.quoteOccurrences())
                .param("rivalContext", location.rivalContext())
                .param("rivalQuote", location.rivalQuote())
                .update();
    }

    private static boolean sameSelection(Passage a, Passage b) {
        return a.sourceBlobSha().equals(b.sourceBlobSha())
                && a.blockId().equals(b.blockId())
                && a.startOffset() == b.startOffset()
                && a.endOffset() == b.endOffset();
    }

    private ApiFailure missingOrConflict(long userId, long id) {
        if (find(userId, id).isEmpty()) return ApiFailure.notFound();
        return new ApiFailure(
                Code.ANNOTATION_CONFLICT,
                "This annotation was changed elsewhere; expectedVersion is not its current version.");
    }

    private Annotation annotation(ResultSet row, int n) throws SQLException {
        return new Annotation(
                row.getLong("id"),
                row.getString("mutation_id"),
                row.getLong("document_id"),
                Type.valueOf(row.getString("type")),
                row.getString("note"),
                Status.valueOf(row.getString("status")),
                row.getInt("version"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                passage(row, "anchor_"),
                new Location(
                        passage(row, "location_"),
                        row.getString("block_sha"),
                        row.getInt("quote_occurrences"),
                        row.getDouble("rival_context"),
                        row.getDouble("rival_quote")),
                row.getString("resolved_blob_sha"),
                row.getString("question"));
    }

    /** The eight passage columns of an anchor or a location, named with {@code prefix}. */
    private Passage passage(ResultSet row, String prefix) throws SQLException {
        return new Passage(
                row.getString(prefix + "source_blob_sha"),
                row.getString(prefix + "block_id"),
                row.getString(prefix + "exact_text"),
                row.getString(prefix + "prefix_text"),
                row.getString(prefix + "suffix_text"),
                row.getInt(prefix + "start_offset"),
                row.getInt(prefix + "end_offset"),
                json.readValue(row.getString(prefix + "heading_path"), new TypeReference<List<String>>() {}));
    }
}
