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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns HIGHLIGHT and CARD annotations: their original anchors, their current locations and status, and the idempotency
 * record of each client creation. Bookmarks share the table (ADR-17) and are written by Bookmarks; disconnecting
 * deletes a connection's rows of every type here.
 */
@Component
public class Annotations {
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

    public record DeletedAnnotations(int highlights, int bookmarks, int cards) {}

    /**
     * Deletes every annotation on a connection's documents: highlights and cards (their anchors, locations, and review
     * logs cascade) and bookmarks. Their mutation records stay, named by no annotation, so a late replay is answered as
     * deleted.
     */
    public DeletedAnnotations deleteOnConnection(long connectionId) {
        var types = db.sql("""
                delete from annotations where document_id in (select id from documents where repository_connection_id = :connectionId)
                returning type""")
                .param("connectionId", connectionId)
                .query(String.class)
                .list();
        return new DeletedAnnotations(
                (int) types.stream().filter("HIGHLIGHT"::equals).count(),
                (int) types.stream().filter("BOOKMARK"::equals).count(),
                (int) types.stream().filter("CARD"::equals).count());
    }

    /** Deletes a user's mutation records (request hashes); called when deleting the account, after its annotations. */
    public void deleteMutations(long userId) {
        db.sql("delete from annotation_mutations where user_id = :userId")
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
            String type,
            String note,
            String status,
            int version,
            Instant createdAt,
            Instant updatedAt,
            Passage anchor,
            Location location,
            String resolvedBlobSha,
            String question,
            String checkedBlobSha) {}

    /** The result of a creation request, and whether it was the replay of an earlier identical request. */
    record Created(Annotation annotation, boolean replayed) {}

    private static final String SELECT = """
        select a.id, a.document_id, a.type, a.note, a.status, a.version, a.created_at, a.updated_at, a.mutation_id, a.resolved_blob_sha,
               n.source_blob_sha as anchor_source_blob_sha, n.block_id as anchor_block_id, n.exact_text as anchor_exact_text,
               n.prefix_text as anchor_prefix_text, n.suffix_text as anchor_suffix_text, n.start_offset as anchor_start_offset,
               n.end_offset as anchor_end_offset, n.heading_path::text as anchor_heading_path,
               l.source_blob_sha as location_source_blob_sha, l.block_id as location_block_id, l.exact_text as location_exact_text,
               l.prefix_text as location_prefix_text, l.suffix_text as location_suffix_text, l.start_offset as location_start_offset,
               l.end_offset as location_end_offset, l.heading_path::text as location_heading_path,
               l.block_sha, l.quote_occurrences, l.rival_context, l.rival_quote, a.question, a.checked_blob_sha
        from annotations a join annotation_anchors n on n.annotation_id = a.id
        join annotation_locations l on l.annotation_id = a.id
        where a.type in ('HIGHLIGHT', 'CARD') and a.user_id = :userId""";

    List<Annotation> notebook(long userId) {
        return db.sql(SELECT + " order by a.created_at, a.id")
                .param("userId", userId)
                .query(this::annotation)
                .list();
    }

    List<Annotation> list(long userId, long documentId) {
        return db.sql(SELECT + " and a.document_id = :documentId order by a.created_at, a.id")
                .param("userId", userId)
                .param("documentId", documentId)
                .query(this::annotation)
                .list();
    }

    Optional<Annotation> find(long userId, long id) {
        return db.sql(SELECT + " and a.id = :id")
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
                        "select request_hash from annotation_mutations where user_id = :userId and mutation_id = :mutationId")
                .param("userId", userId)
                .param("mutationId", mutationId)
                .query((row, n) -> row.getBytes("request_hash"))
                .optional();
        if (hash.isEmpty()) return Optional.empty();
        if (!MessageDigest.isEqual(hash.get(), requestHash)) {
            throw new ApiFailure(
                    Code.MUTATION_ID_REUSED, "This mutationId was already used for a different annotation request.");
        }
        var created = db.sql(SELECT + " and a.mutation_id = :mutationId")
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
            String type,
            String question) {
        var anchor = location.passage();
        return transaction.execute(status -> {
            connections.lockForDocumentWrite(userId, documentId);
            Instant now = Instant.now();
            int claimed = db.sql("""
                    insert into annotation_mutations (user_id, mutation_id, request_hash, created_at)
                    values (:userId, :mutationId, :hash, :now) on conflict do nothing""")
                    .param("userId", userId)
                    .param("mutationId", mutationId)
                    .param("hash", requestHash)
                    .param("now", Timestamp.from(now))
                    .update();
            if (claimed == 0)
                return new Created(replay(userId, mutationId, requestHash).orElseThrow(), true);
            long id = db.sql("""
                    insert into annotations (user_id, document_id, type, note, created_at, updated_at, resolved_blob_sha, question,
                                             checked_blob_sha, mutation_id)
                    values (:userId, :documentId, :type, :note, :now, :now, :sha, :question, case when :type = 'CARD' then :sha end,
                            :mutationId) returning id""")
                    .param("userId", userId)
                    .param("documentId", documentId)
                    .param("sha", anchor.sourceBlobSha())
                    .param("mutationId", mutationId)
                    .param("note", note)
                    .param("type", type)
                    .param("question", question)
                    .param("now", Timestamp.from(now))
                    .query(Long.class)
                    .single();
            db.sql("""
                    insert into annotation_anchors (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text,
                                                    start_offset, end_offset, heading_path)
                    values (:id, :sha, :block, :exact, :prefix, :suffix, :start, :end, cast(:headings as jsonb))""")
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
        int updated = db.sql("""
                update annotations set note = :note, version = version + 1, updated_at = :now
                where id = :id and user_id = :userId and type in ('HIGHLIGHT', 'CARD') and version = :expected""")
                .param("note", note)
                .param("now", Timestamp.from(Instant.now()))
                .param("id", id)
                .param("userId", userId)
                .param("expected", expectedVersion)
                .update();
        if (updated == 0) throw missingOrConflict(userId, id);
        return find(userId, id).orElseThrow();
    }

    void delete(long userId, long id, int expectedVersion) {
        int deleted = db.sql(
                        "delete from annotations where id = :id and user_id = :userId and type in ('HIGHLIGHT', 'CARD') and version = :expected")
                .param("id", id)
                .param("userId", userId)
                .param("expected", expectedVersion)
                .update();
        if (deleted == 0) throw missingOrConflict(userId, id);
    }

    /**
     * Records resolving a highlight in version {@code blobSha}, only while it is still resolved against the version and
     * at the user-edit version {@code annotation} was read with, so a concurrent resolution, a reattachment, or a
     * confirmation wins. Empty {@code found} orphans it and keeps its last location. The user-edit version is
     * unchanged: re-anchoring is not an edit. Returns whether this call applied.
     */
    boolean resolved(Annotation annotation, String blobSha, Optional<Location> found) {
        String status = found.map(
                        location -> sameSelection(location.passage(), annotation.anchor()) ? "ANCHORED" : "REANCHORED")
                .orElse("ORPHANED");
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            int updated = db.sql("""
                    update annotations set status = :status, resolved_blob_sha = :sha, checked_blob_sha = null
                    where id = :id and type in ('HIGHLIGHT', 'CARD') and resolved_blob_sha = :expected and version = :version""")
                    .param("status", status)
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
        transaction.executeWithoutResult(tx -> {
            int updated = db.sql("""
                    update annotations set status = 'REANCHORED', resolved_blob_sha = :sha, checked_blob_sha = null, version = version + 1, updated_at = :now
                    where id = :id and user_id = :userId and type in ('HIGHLIGHT', 'CARD') and version = :expected""")
                    .param("sha", location.passage().sourceBlobSha())
                    .param("now", Timestamp.from(Instant.now()))
                    .param("id", id)
                    .param("userId", userId)
                    .param("expected", expectedVersion)
                    .update();
            if (updated == 0) throw missingOrConflict(userId, id);
            writeLocation(id, location);
        });
        return find(userId, id).orElseThrow();
    }

    Annotation checkCard(long userId, long id, int expectedVersion, String blobSha) {
        int updated = db.sql("""
                update annotations a set checked_blob_sha = :sha, version = version + 1, updated_at = now()
                from documents d where a.document_id = d.id and a.id = :id and a.user_id = :userId and a.type = 'CARD'
                and a.version = :expected and a.resolved_blob_sha = :sha and a.status <> 'ORPHANED'
                and d.current_blob_sha = :sha and d.deleted_at is null""")
                .param("sha", blobSha)
                .param("id", id)
                .param("userId", userId)
                .param("expected", expectedVersion)
                .update();
        if (updated == 0) {
            var card = find(userId, id).filter(a -> a.type().equals("CARD")).orElseThrow(ApiFailure::notFound);
            // A stale version is another edit landing first, as for edit, delete, and reattach.
            if (card.version() != expectedVersion) throw missingOrConflict(userId, id);
            throw new ApiFailure(
                    Code.CARD_CHANGED, "Check this card in its current version; reattach an orphan before confirming.");
        }
        return find(userId, id).orElseThrow();
    }

    private void writeLocation(long id, Location location) {
        var passage = location.passage();
        db.sql("""
                insert into annotation_locations (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text, start_offset,
                                                  end_offset, heading_path, block_sha, quote_occurrences, rival_context, rival_quote)
                values (:id, :sha, :block, :exact, :prefix, :suffix, :start, :end, cast(:headings as jsonb), :blockSha, :occurrences,
                        :rivalContext, :rivalQuote)
                on conflict (annotation_id) do update set source_blob_sha = excluded.source_blob_sha, block_id = excluded.block_id,
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
                "This annotation was changed elsewhere; reload it and choose which text to keep.");
    }

    private Annotation annotation(ResultSet row, int n) throws SQLException {
        return new Annotation(
                row.getLong("id"),
                row.getString("mutation_id"),
                row.getLong("document_id"),
                row.getString("type"),
                row.getString("note"),
                row.getString("status"),
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
                row.getString("question"),
                row.getString("checked_blob_sha"));
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
