package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.repository.RepositoryConnections;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns review_log, the durable record of every grade; never scheduling, which stays on the phone (ADR-13). */
@Component
public class Reviews {
    private static final int SESSION_LIMIT = 40;
    private static final String SELECT = """
        select r.mutation_id, a.mutation_id as card_mutation_id, r.grade, r.reviewed_at, r.blob_sha
        from review_log r join annotations a on a.id = r.annotation_id
        where r.user_id = :userId""";

    private final JdbcClient db;
    private final Annotations annotations;
    private final TransactionTemplate transaction;
    /** One repeatable, read-only database snapshot: a concurrent delete cannot split cards from their logs. */
    private final TransactionTemplate snapshot;

    private final RepositoryConnections connections;

    public Reviews(
            JdbcClient db,
            Annotations annotations,
            TransactionTemplate transaction,
            RepositoryConnections connections) {
        this.db = db;
        this.annotations = annotations;
        this.transaction = transaction;
        this.snapshot = new TransactionTemplate(transaction.getTransactionManager());
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        this.connections = connections;
    }

    record Review(String mutationId, String cardMutationId, int grade, Instant reviewedAt, String blobSha) {}

    record Notebook(List<Annotations.Annotation> annotations, List<Review> reviews, int sessionLimit) {}

    Notebook notebook(long userId) {
        return snapshot.execute(tx -> new Notebook(
                annotations.notebook(userId),
                db.sql(SELECT + " order by r.reviewed_at, r.mutation_id")
                        .param("userId", userId)
                        .query(Reviews::review)
                        .list(),
                SESSION_LIMIT));
    }

    /**
     * Records a grade of the card {@code cardId} once per client {@code mutation}: a replay returns the stored review,
     * even if the note changed after it was committed; the same id with other content is 409 MUTATION_ID_REUSED. A new
     * grade of an answer that is not the card's checked, current version is 409 CARD_CHANGED. Holds the card's
     * connection lock while it writes, so a refresh or disconnect waits for it; a card that is not this user's, or was
     * disconnected meanwhile, is 404.
     */
    Review grade(long userId, long cardId, UUID mutation, int grade, Instant reviewedAt, String blobSha) {
        return transaction.execute(tx -> {
            // Same lock order as repository refresh/disconnect: connection first, then annotations.
            long documentId = db.sql(
                            "select document_id from annotations where id = :id and user_id = :userId and type = 'CARD'")
                    .param("id", cardId)
                    .param("userId", userId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(ApiFailure::notFound);
            connections.lockForDocumentWrite(userId, documentId);
            var current = db.sql("""
                    select a.checked_blob_sha, d.current_blob_sha, d.deleted_at is not null as deleted, a.status
                    from annotations a join documents d on d.id = a.document_id
                    where a.id = :id and a.user_id = :userId and a.type = 'CARD' for update of a""")
                    .param("id", cardId)
                    .param("userId", userId)
                    .query((row, n) -> new Current(
                            row.getString("checked_blob_sha"),
                            row.getString("current_blob_sha"),
                            row.getBoolean("deleted"),
                            row.getString("status")))
                    .optional()
                    .orElseThrow(ApiFailure::notFound);
            int inserted = db.sql("""
                    insert into review_log(user_id, mutation_id, annotation_id, grade, reviewed_at, blob_sha)
                    values (:userId, :mutation, :id, :grade, :at, :sha) on conflict do nothing""")
                    .param("userId", userId)
                    .param("mutation", mutation)
                    .param("id", cardId)
                    .param("grade", grade)
                    .param("at", Timestamp.from(reviewedAt))
                    .param("sha", blobSha)
                    .update();
            var stored = db.sql(
                            "select annotation_id, grade, reviewed_at, blob_sha from review_log where user_id = :userId and mutation_id = :mutation")
                    .param("userId", userId)
                    .param("mutation", mutation)
                    .query((row, n) -> new Stored(
                            row.getLong("annotation_id"),
                            row.getInt("grade"),
                            row.getTimestamp("reviewed_at").toInstant(),
                            row.getString("blob_sha")))
                    .single();
            if (stored.id() != cardId
                    || stored.grade() != grade
                    || !stored.at().equals(reviewedAt)
                    || !stored.sha().equals(blobSha)) {
                throw new ApiFailure(Code.MUTATION_ID_REUSED, "This mutationId belongs to a different review.");
            }
            if (inserted != 0
                    && (current.deleted()
                            || current.status().equals("ORPHANED")
                            || !blobSha.equals(current.checked())
                            || !blobSha.equals(current.sha()))) {
                throw new ApiFailure(Code.CARD_CHANGED, "Check this card in the current note before reviewing it.");
            }
            return db.sql(SELECT + " and r.mutation_id = :mutation")
                    .param("userId", userId)
                    .param("mutation", mutation)
                    .query(Reviews::review)
                    .single();
        });
    }

    private record Current(String checked, String sha, boolean deleted, String status) {}

    private record Stored(long id, int grade, Instant at, String sha) {}

    private static Review review(ResultSet row, int n) throws SQLException {
        return new Review(
                row.getString("mutation_id"),
                row.getString("card_mutation_id"),
                row.getInt("grade"),
                row.getTimestamp("reviewed_at").toInstant(),
                row.getString("blob_sha"));
    }
}
