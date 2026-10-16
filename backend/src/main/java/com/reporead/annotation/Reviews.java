package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
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

    public Reviews(JdbcClient db, Annotations annotations, TransactionTemplate transaction) {
        this.db = db;
        this.annotations = annotations;
        this.transaction = transaction;
        this.snapshot = new TransactionTemplate(transaction.getTransactionManager());
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    record Review(String mutationId, String cardMutationId, int grade, Instant reviewedAt, String blobSha) {}

    record Notebook(List<Annotations.Annotation> annotations, List<Review> reviews, int sessionLimit) {}

    Notebook notebook(long userId) {
        return snapshot.execute(tx -> new Notebook(
                annotations.notebook(userId),
                db.sql(SELECT + " order by r.reviewed_at, r.mutation_id")
                        .param("userId", userId)
                        .query(Review.class)
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
            annotations.lockForWrite(userId, cardId);
            var current = db.sql("""
                    select a.checked_blob_sha, d.current_blob_sha, d.deleted_at is not null as deleted, a.status
                    from annotations a join documents d on d.id = a.document_id
                    where a.id = :id and a.user_id = :userId and a.type = 'CARD' for update of a""")
                    .param("id", cardId)
                    .param("userId", userId)
                    .query(Current.class)
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
                    .query(Stored.class)
                    .single();
            if (stored.annotationId() != cardId
                    || stored.grade() != grade
                    || !stored.reviewedAt().equals(reviewedAt)
                    || !stored.blobSha().equals(blobSha)) {
                throw new ApiFailure(Code.MUTATION_ID_REUSED, "This mutationId belongs to a different review.");
            }
            if (inserted != 0
                    && (current.deleted()
                            || current.status() == Annotations.Status.ORPHANED
                            || !blobSha.equals(current.checkedBlobSha())
                            || !blobSha.equals(current.currentBlobSha()))) {
                throw new ApiFailure(Code.CARD_CHANGED, "Check this card in the current note before reviewing it.");
            }
            return db.sql(SELECT + " and r.mutation_id = :mutation")
                    .param("userId", userId)
                    .param("mutation", mutation)
                    .query(Review.class)
                    .single();
        });
    }

    private record Current(String checkedBlobSha, String currentBlobSha, boolean deleted, Annotations.Status status) {}

    private record Stored(long annotationId, int grade, Instant reviewedAt, String blobSha) {}
}
