package com.reporead.annotation;

import com.reporead.ApiFailure;
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

/** Owns HIGHLIGHT annotations, their anchors, and the idempotency record of each client creation. */
@Component
public class Annotations {
    private final JdbcClient db;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public Annotations(JdbcClient db, JsonMapper json, TransactionTemplate transaction) {
        this.db = db;
        this.json = json;
        this.transaction = transaction;
    }

    public record Anchor(String sourceBlobSha, String blockId, String exactText, String prefixText, String suffixText,
                         int startOffset, int endOffset, List<String> headingPath) {}

    public record Annotation(long id, long documentId, String type, String note, String status, int version,
                             Instant createdAt, Instant updatedAt, Anchor anchor) {}

    /** The result of a creation request, and whether it was the replay of an earlier identical request. */
    record Created(Annotation annotation, boolean replayed) {}

    private static final String SELECT = """
        select a.id, a.document_id, a.type, a.note, a.status, a.version, a.created_at, a.updated_at,
               n.source_blob_sha, n.block_id, n.exact_text, n.prefix_text, n.suffix_text, n.start_offset, n.end_offset, n.heading_path::text
        from annotations a join annotation_anchors n on n.annotation_id = a.id
        where a.type = 'HIGHLIGHT' and a.user_id = :userId""";

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
    Created create(long userId, long documentId, UUID mutationId, byte[] requestHash, Anchor anchor, String note) {
        return transaction.execute(status -> {
            Instant now = Instant.now();
            int claimed = db.sql("""
                    insert into annotation_mutations (user_id, mutation_id, request_hash, created_at)
                    values (:userId, :mutationId, :hash, :now) on conflict do nothing""")
                .param("userId", userId).param("mutationId", mutationId).param("hash", requestHash).param("now", Timestamp.from(now)).update();
            if (claimed == 0) return new Created(replay(userId, mutationId, requestHash).orElseThrow(), true);
            long id = db.sql("""
                    insert into annotations (user_id, document_id, source_blob_sha, type, note, created_at, updated_at)
                    values (:userId, :documentId, :sha, 'HIGHLIGHT', :note, :now, :now) returning id""")
                .param("userId", userId).param("documentId", documentId).param("sha", anchor.sourceBlobSha())
                .param("note", note).param("now", Timestamp.from(now)).query(Long.class).single();
            db.sql("""
                    insert into annotation_anchors (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text,
                                                    start_offset, end_offset, heading_path)
                    values (:id, :sha, :block, :exact, :prefix, :suffix, :start, :end, cast(:headings as jsonb))""")
                .param("id", id).param("sha", anchor.sourceBlobSha()).param("block", anchor.blockId()).param("exact", anchor.exactText())
                .param("prefix", anchor.prefixText()).param("suffix", anchor.suffixText()).param("start", anchor.startOffset())
                .param("end", anchor.endOffset()).param("headings", json.writeValueAsString(anchor.headingPath())).update();
            db.sql("update annotation_mutations set annotation_id = :id where user_id = :userId and mutation_id = :mutationId")
                .param("id", id).param("userId", userId).param("mutationId", mutationId).update();
            return new Created(find(userId, id).orElseThrow(), false);
        });
    }

    /** Optimistic update: applies only at [expectedVersion]; 409 if another edit landed first, 404 if absent. */
    Annotation updateNote(long userId, long id, int expectedVersion, String note) {
        int updated = db.sql("""
                update annotations set note = :note, version = version + 1, updated_at = :now
                where id = :id and user_id = :userId and type = 'HIGHLIGHT' and version = :expected""")
            .param("note", note).param("now", Timestamp.from(Instant.now())).param("id", id).param("userId", userId)
            .param("expected", expectedVersion).update();
        if (updated == 0) throw missingOrConflict(userId, id);
        return find(userId, id).orElseThrow();
    }

    void delete(long userId, long id, int expectedVersion) {
        int deleted = db.sql("delete from annotations where id = :id and user_id = :userId and type = 'HIGHLIGHT' and version = :expected")
            .param("id", id).param("userId", userId).param("expected", expectedVersion).update();
        if (deleted == 0) throw missingOrConflict(userId, id);
    }

    private ApiFailure missingOrConflict(long userId, long id) {
        if (find(userId, id).isEmpty()) return new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
        return new ApiFailure(HttpStatus.CONFLICT, "ANNOTATION_CONFLICT", "This annotation was changed elsewhere; reload it and choose which text to keep.");
    }

    private Annotation annotation(ResultSet row, int n) throws SQLException {
        var anchor = new Anchor(row.getString(9), row.getString(10), row.getString(11), row.getString(12), row.getString(13),
            row.getInt(14), row.getInt(15), json.readValue(row.getString(16), new TypeReference<List<String>>() {}));
        return new Annotation(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4), row.getString(5), row.getInt(6),
            row.getTimestamp(7).toInstant(), row.getTimestamp(8).toInstant(), anchor);
    }
}
