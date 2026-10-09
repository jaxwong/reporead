package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.repository.RepositoryConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owns the durable review log, never scheduling. Every route makes zero external calls. */
@RestController
public class Reviews {
    private static final Logger LOG = LoggerFactory.getLogger(Reviews.class);
    private static final int SESSION_LIMIT = 40;
    private final JdbcClient db;
    private final Annotations annotations;
    private final TransactionTemplate transaction;
    private final RepositoryConnections connections;

    public Reviews(JdbcClient db, Annotations annotations, TransactionTemplate transaction, RepositoryConnections connections) {
        this.db = db;
        this.annotations = annotations;
        this.transaction = transaction;
        this.connections = connections;
    }

    record Review(String mutationId, String cardMutationId, int grade, Instant reviewedAt, String blobSha) {}
    record Notebook(List<Annotations.Annotation> annotations, List<Review> reviews, int sessionLimit) {}
    record GradeRequest(String mutationId, Integer grade, Instant reviewedAt, String blobSha) {}

    private static final String SELECT = """
        select r.mutation_id, m.mutation_id, r.grade, r.reviewed_at, r.blob_sha
        from review_log r join annotation_mutations m on m.annotation_id = r.annotation_id and m.user_id = r.user_id
        where r.user_id = :userId""";

    @GetMapping("/api/notebook")
    Notebook notebook(@AuthenticationPrincipal AppUser user) {
        // One repeatable database snapshot: a concurrent delete cannot split cards from their logs.
        return transaction.execute(tx -> {
            db.sql("set transaction isolation level repeatable read").update();
            return new Notebook(annotations.notebook(user.id()), db.sql(SELECT + " order by r.reviewed_at, r.mutation_id")
                .param("userId", user.id()).query(Reviews::review).list(), SESSION_LIMIT);
        });
    }

    @PostMapping("/api/annotations/{id}/reviews")
    Review grade(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody GradeRequest body) {
        UUID mutation;
        try { mutation = UUID.fromString(body.mutationId() == null ? "" : body.mutationId()); }
        catch (IllegalArgumentException error) { throw invalid("mutationId must be a UUID."); }
        if (body.grade() == null || body.grade() < 0 || body.grade() > 5 || body.reviewedAt() == null
            || (body.reviewedAt().isBefore(Instant.EPOCH) || body.reviewedAt().getNano() % 1_000_000 != 0 || body.reviewedAt().isAfter(Instant.now().plusSeconds(300))) || body.blobSha() == null || !body.blobSha().matches("[0-9a-f]{40}")) {
            throw invalid("A review needs grade 0–5, a millisecond reviewedAt from 1970 through five minutes ahead, and the verified answer's blob SHA.");
        }
        var result = transaction.execute(tx -> {
            // Same lock order as repository refresh/disconnect: connection first, then annotations.
            long connectionId = db.sql("""
                    select d.repository_connection_id from annotations a join documents d on d.id = a.document_id
                    where a.id = :id and a.user_id = :userId and a.type = 'CARD'""")
                .param("id", id).param("userId", user.id()).query(Long.class).optional()
                .orElseThrow(() -> new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Card not found."));
            connections.lockForSync(user.id(), connectionId);
            var current = db.sql("""
                    select a.checked_blob_sha, d.current_blob_sha, d.deleted_at is not null, a.status
                    from annotations a join documents d on d.id = a.document_id
                    where a.id = :id and a.user_id = :userId and a.type = 'CARD' for update of a""")
                .param("id", id).param("userId", user.id())
                .query((row, n) -> new Current(row.getString(1), row.getString(2), row.getBoolean(3), row.getString(4))).optional()
                .orElseThrow(() -> new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Card not found."));
            int inserted = db.sql("""
                    insert into review_log(user_id, mutation_id, annotation_id, grade, reviewed_at, blob_sha)
                    values (:userId, :mutation, :id, :grade, :at, :sha) on conflict do nothing""")
                .param("userId", user.id()).param("mutation", mutation).param("id", id).param("grade", body.grade())
                .param("at", Timestamp.from(body.reviewedAt())).param("sha", body.blobSha()).update();
            var stored = db.sql("select annotation_id, grade, reviewed_at, blob_sha from review_log where user_id = :userId and mutation_id = :mutation")
                .param("userId", user.id()).param("mutation", mutation)
                .query((row, n) -> new Stored(row.getLong(1), row.getInt(2), row.getTimestamp(3).toInstant(), row.getString(4))).single();
            if (stored.id() != id || stored.grade() != body.grade() || !stored.at().equals(body.reviewedAt()) || !stored.sha().equals(body.blobSha())) {
                throw new ApiFailure(HttpStatus.CONFLICT, "MUTATION_ID_REUSED", "This mutationId belongs to a different review.");
            }
            // Replays remain successful even if the note changed after the original grade was committed.
            if (inserted != 0 && (current.deleted() || current.status().equals("ORPHANED")
                || !body.blobSha().equals(current.checked()) || !body.blobSha().equals(current.sha()))) {
                throw new ApiFailure(HttpStatus.CONFLICT, "CARD_CHANGED", "Check this card in the current note before reviewing it.");
            }
            return db.sql(SELECT + " and r.mutation_id = :mutation").param("userId", user.id()).param("mutation", mutation)
                .query(Reviews::review).single();
        });
        LOG.info("Review stored or replayed; userId={} annotationId={} mutationId={} grade={}", user.id(), id, mutation, body.grade());
        return result;
    }

    private record Current(String checked, String sha, boolean deleted, String status) {}
    private record Stored(long id, int grade, Instant at, String sha) {}

    private static Review review(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new Review(row.getString(1), row.getString(2), row.getInt(3), row.getTimestamp(4).toInstant(), row.getString(5));
    }

    private static ApiFailure invalid(String message) { return new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REVIEW", message); }
}
