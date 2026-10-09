package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns HIGHLIGHT and CARD annotations: their original anchors, their current locations and status, and the idempotency record
 * of each client creation.
 */
@Component
public class Annotations {
    private final JdbcClient db;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public record Deleted(int highlights, int bookmarks, int cards) {}

    /**
     * Deletes every annotation on a connection's documents: highlights (their anchors and locations cascade) and
     * bookmarks. Their mutation records keep only the request hash, so a late replay is answered as deleted.
     */
    public Deleted deleteOnConnection(long connectionId) {
        var types = db.sql("""
                delete from annotations where document_id in (select id from documents where repository_connection_id = :connectionId)
                returning type""")
            .param("connectionId", connectionId).query(String.class).list();
        return new Deleted((int) types.stream().filter("HIGHLIGHT"::equals).count(), (int) types.stream().filter("BOOKMARK"::equals).count(), (int) types.stream().filter("CARD"::equals).count());
    }

    /** Deletes a user's mutation records (request hashes); called when deleting the account, after its annotations. */
    public void deleteMutations(long userId) {
        db.sql("delete from annotation_mutations where user_id = :userId").param("userId", userId).update();
    }

    public Annotations(JdbcClient db, JsonMapper json, TransactionTemplate transaction) {
        this.db = db;
        this.json = json;
        this.transaction = transaction;
    }

    /**
     * A passage in one source version. The last four fields say how distinguishable it was in that version:
     * [blockSha] is the SHA-256 of its block's text when no other block has that text, [quoteOccurrences] how often its
     * exact text occurs, [rivalContext] how similar the best other exact occurrence's context is (0 when unique), and
     * [rivalQuote] how similar the closest look-alike in another block is (0 when none, 1 when there were too many to
     * compare). They are null where unknown
     * (anchors made before Stage 4); the resolver then treats the passage as indistinguishable.
     */
    public record Anchor(String sourceBlobSha, String blockId, String exactText, String prefixText, String suffixText,
                         int startOffset, int endOffset, List<String> headingPath, @JsonIgnore String blockSha,
                         @JsonIgnore Integer quoteOccurrences, @JsonIgnore Double rivalContext, @JsonIgnore Double rivalQuote) {}

    /**
     * {@code mutationId} is the client id that created it, so a client can match a pending local copy to it.
     * {@code anchor} is the original selection, never changed. {@code location} is the latest trusted location, and
     * {@code status} says what it means for version {@code resolvedBlobSha}: ANCHORED (the original selection, in that
     * version), REANCHORED (found again in that version, by the server or by the user), or ORPHANED (not reliably found
     * in that version; {@code location} is the last place the passage was known to be).
     */
    public record Annotation(long id, String mutationId, long documentId, String type, String note, String status, int version,
                             Instant createdAt, Instant updatedAt, Anchor anchor, Anchor location, String resolvedBlobSha, String question, String checkedBlobSha,
                             String title, String path, String currentBlobSha, boolean deleted) {}

    /** The result of a creation request, and whether it was the replay of an earlier identical request. */
    record Created(Annotation annotation, boolean replayed) {}

    private static final String SELECT = """
        select a.id, a.document_id, a.type, a.note, a.status, a.version, a.created_at, a.updated_at, m.mutation_id, a.resolved_blob_sha,
               n.source_blob_sha, n.block_id, n.exact_text, n.prefix_text, n.suffix_text, n.start_offset, n.end_offset, n.heading_path::text,
               l.source_blob_sha, l.block_id, l.exact_text, l.prefix_text, l.suffix_text, l.start_offset, l.end_offset, l.heading_path::text,
               l.block_sha, l.quote_occurrences, l.rival_context, l.rival_quote, a.question, a.checked_blob_sha,
               d.title, d.path, d.current_blob_sha, d.deleted_at is not null
        from annotations a join documents d on d.id = a.document_id join annotation_anchors n on n.annotation_id = a.id
        join annotation_locations l on l.annotation_id = a.id
        join annotation_mutations m on m.annotation_id = a.id and m.user_id = a.user_id
        where a.type in ('HIGHLIGHT', 'CARD') and a.user_id = :userId""";

    List<Annotation> notebook(long userId) {
        return db.sql(SELECT + " order by a.created_at, a.id").param("userId", userId).query(this::annotation).list();
    }

    List<Annotation> list(long userId, long documentId) {
        return db.sql(SELECT + " and a.document_id = :documentId order by a.created_at, a.id")
            .param("userId", userId).param("documentId", documentId).query(this::annotation).list();
    }

    Optional<Annotation> find(long userId, long id) {
        return db.sql(SELECT + " and a.id = :id").param("userId", userId).param("id", id).query(this::annotation).optional();
    }

    /**
     * The earlier result for this mutation id, if any. Same request: the original annotation. Different content: 409.
     * The annotation was since deleted: 410, so a late replay cannot resurrect it.
     */
    Optional<Annotation> replay(long userId, UUID mutationId, byte[] requestHash) {
        record Mutation(byte[] hash, Long annotationId) {}
        var mutation = db.sql("select request_hash, annotation_id from annotation_mutations where user_id = :userId and mutation_id = :mutationId")
            .param("userId", userId).param("mutationId", mutationId)
            .query((row, n) -> new Mutation(row.getBytes(1), (Long) row.getObject(2))).optional();
        if (mutation.isEmpty()) return Optional.empty();
        if (!MessageDigest.isEqual(mutation.get().hash(), requestHash)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "MUTATION_ID_REUSED", "This mutationId was already used for a different annotation request.");
        }
        if (mutation.get().annotationId() == null) {
            throw new ApiFailure(HttpStatus.GONE, "ANNOTATION_DELETED", "The annotation created by this mutation has since been deleted.");
        }
        return Optional.of(find(userId, mutation.get().annotationId()).orElseThrow(() -> new IllegalStateException(
            "Mutation " + mutationId + " for user " + userId + " references missing annotation " + mutation.get().annotationId())));
    }

    /**
     * Creates the annotation, its anchor, and the mutation record in one transaction. A concurrent request with the same
     * mutation id waits on the primary key and then resolves as a replay.
     */
    Created create(long userId, long documentId, UUID mutationId, byte[] requestHash, Anchor anchor, String note, String type, String question) {
        return transaction.execute(status -> {
            Instant now = Instant.now();
            int claimed = db.sql("""
                    insert into annotation_mutations (user_id, mutation_id, request_hash, created_at)
                    values (:userId, :mutationId, :hash, :now) on conflict do nothing""")
                .param("userId", userId).param("mutationId", mutationId).param("hash", requestHash).param("now", Timestamp.from(now)).update();
            if (claimed == 0) return new Created(replay(userId, mutationId, requestHash).orElseThrow(), true);
            long id = db.sql("""
                    insert into annotations (user_id, document_id, source_blob_sha, type, note, created_at, updated_at, resolved_blob_sha, question, checked_blob_sha)
                    values (:userId, :documentId, :sha, :type, :note, :now, :now, :sha, :question, case when :type = 'CARD' then :sha end) returning id""")
                .param("userId", userId).param("documentId", documentId).param("sha", anchor.sourceBlobSha())
                .param("note", note).param("type", type).param("question", question).param("now", Timestamp.from(now)).query(Long.class).single();
            db.sql("""
                    insert into annotation_anchors (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text,
                                                    start_offset, end_offset, heading_path)
                    values (:id, :sha, :block, :exact, :prefix, :suffix, :start, :end, cast(:headings as jsonb))""")
                .param("id", id).param("sha", anchor.sourceBlobSha()).param("block", anchor.blockId()).param("exact", anchor.exactText())
                .param("prefix", anchor.prefixText()).param("suffix", anchor.suffixText()).param("start", anchor.startOffset())
                .param("end", anchor.endOffset()).param("headings", json.writeValueAsString(anchor.headingPath())).update();
            writeLocation(id, anchor);
            db.sql("update annotation_mutations set annotation_id = :id where user_id = :userId and mutation_id = :mutationId")
                .param("id", id).param("userId", userId).param("mutationId", mutationId).update();
            return new Created(find(userId, id).orElseThrow(), false);
        });
    }

    /** Optimistic update: applies only at [expectedVersion]; 409 if another edit landed first, 404 if absent. */
    Annotation updateNote(long userId, long id, int expectedVersion, String note) {
        int updated = db.sql("""
                update annotations set note = :note, version = version + 1, updated_at = :now
                where id = :id and user_id = :userId and type in ('HIGHLIGHT', 'CARD') and version = :expected""")
            .param("note", note).param("now", Timestamp.from(Instant.now())).param("id", id).param("userId", userId)
            .param("expected", expectedVersion).update();
        if (updated == 0) throw missingOrConflict(userId, id);
        return find(userId, id).orElseThrow();
    }

    void delete(long userId, long id, int expectedVersion) {
        int deleted = db.sql("delete from annotations where id = :id and user_id = :userId and type in ('HIGHLIGHT', 'CARD') and version = :expected")
            .param("id", id).param("userId", userId).param("expected", expectedVersion).update();
        if (deleted == 0) throw missingOrConflict(userId, id);
    }

    /**
     * Records resolving a highlight in version [blobSha], only while it is still resolved against [expectedBlobSha], so
     * a concurrent resolution or a reattachment wins. Empty [found] orphans it and keeps its last location. The user-edit
     * version is unchanged: re-anchoring is not an edit. Returns whether this call applied.
     */
    boolean resolved(Annotation annotation, String blobSha, Optional<Anchor> found) {
        String status = found.map(location -> sameSelection(location, annotation.anchor()) ? "ANCHORED" : "REANCHORED").orElse("ORPHANED");
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            int updated = db.sql("""
                    update annotations set status = :status, resolved_blob_sha = :sha, checked_blob_sha = null
                    where id = :id and type in ('HIGHLIGHT', 'CARD') and resolved_blob_sha = :expected""")
                .param("status", status).param("sha", blobSha).param("id", annotation.id()).param("expected", annotation.resolvedBlobSha()).update();
            if (updated == 0) return false;
            found.ifPresent(location -> writeLocation(annotation.id(), location));
            return true;
        }));
    }

    /** Fills a pre-Stage-4 location's distinguishability, computed from its own version; changes nothing else. */
    void fillEvidence(long id, Anchor location) {
        db.sql("""
                update annotation_locations set block_sha = :blockSha, quote_occurrences = :occurrences, rival_context = :rivalContext,
                       rival_quote = :rivalQuote
                where annotation_id = :id and source_blob_sha = :sha and block_id = :block and start_offset = :start and rival_context is null""")
            .param("blockSha", location.blockSha()).param("occurrences", location.quoteOccurrences())
            .param("rivalContext", location.rivalContext()).param("rivalQuote", location.rivalQuote()).param("id", id)
            .param("sha", location.sourceBlobSha()).param("block", location.blockId()).param("start", location.startOffset()).update();
    }

    /** The user's own placement: versioned like a note edit (409 if another change landed first, 404 if absent). */
    Annotation reattach(long userId, long id, int expectedVersion, Anchor location) {
        transaction.executeWithoutResult(tx -> {
            int updated = db.sql("""
                    update annotations set status = 'REANCHORED', resolved_blob_sha = :sha, checked_blob_sha = null, version = version + 1, updated_at = :now
                    where id = :id and user_id = :userId and type in ('HIGHLIGHT', 'CARD') and version = :expected""")
                .param("sha", location.sourceBlobSha()).param("now", Timestamp.from(Instant.now())).param("id", id)
                .param("userId", userId).param("expected", expectedVersion).update();
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
            .param("sha", blobSha).param("id", id).param("userId", userId).param("expected", expectedVersion).update();
        if (updated == 0) {
            if (find(userId, id).filter(a -> a.type().equals("CARD")).isEmpty()) throw new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
            throw new ApiFailure(HttpStatus.CONFLICT, "CARD_CHANGED", "Check this card in its current version; reattach an orphan before confirming.");
        }
        return find(userId, id).orElseThrow();
    }

    private void writeLocation(long id, Anchor location) {
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
            .param("id", id).param("sha", location.sourceBlobSha()).param("block", location.blockId()).param("exact", location.exactText())
            .param("prefix", location.prefixText()).param("suffix", location.suffixText()).param("start", location.startOffset())
            .param("end", location.endOffset()).param("headings", json.writeValueAsString(location.headingPath()))
            .param("blockSha", location.blockSha()).param("occurrences", location.quoteOccurrences())
            .param("rivalContext", location.rivalContext()).param("rivalQuote", location.rivalQuote()).update();
    }

    private static boolean sameSelection(Anchor a, Anchor b) {
        return a.sourceBlobSha().equals(b.sourceBlobSha()) && a.blockId().equals(b.blockId())
            && a.startOffset() == b.startOffset() && a.endOffset() == b.endOffset();
    }

    private ApiFailure missingOrConflict(long userId, long id) {
        if (find(userId, id).isEmpty()) return new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
        return new ApiFailure(HttpStatus.CONFLICT, "ANNOTATION_CONFLICT", "This annotation was changed elsewhere; reload it and choose which text to keep.");
    }

    private Annotation annotation(ResultSet row, int n) throws SQLException {
        var location = anchor(row, 19);
        location = new Anchor(location.sourceBlobSha(), location.blockId(), location.exactText(), location.prefixText(),
            location.suffixText(), location.startOffset(), location.endOffset(), location.headingPath(), row.getString(27),
            (Integer) row.getObject(28), (Double) row.getObject(29), (Double) row.getObject(30));
        return new Annotation(row.getLong(1), row.getString(9), row.getLong(2), row.getString(3), row.getString(4), row.getString(5),
            row.getInt(6), row.getTimestamp(7).toInstant(), row.getTimestamp(8).toInstant(), anchor(row, 11), location, row.getString(10), row.getString(31), row.getString(32),
            row.getString(33), row.getString(34), row.getString(35), row.getBoolean(36));
    }

    /** The eight anchor columns starting at [first]; distinguishability is not part of an original anchor. */
    private Anchor anchor(ResultSet row, int first) throws SQLException {
        return new Anchor(row.getString(first), row.getString(first + 1), row.getString(first + 2), row.getString(first + 3),
            row.getString(first + 4), row.getInt(first + 5), row.getInt(first + 6),
            json.readValue(row.getString(first + 7), new TypeReference<List<String>>() {}), null, null, null, null);
    }
}
