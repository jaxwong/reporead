package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns review_log, the durable record of every grade; never scheduling, which stays on the phone (ADR-13). */
@Component
public class Reviews {
    private static final String SELECT = """
        SELECT r.mutation_id, a.mutation_id AS card_mutation_id, r.grade, r.reviewed_at, r.blob_sha
        FROM review_log r JOIN annotations a ON a.id = r.annotation_id
        WHERE r.user_id = :userId""";

    private final JdbcClient db;
    private final Annotations annotations;
    private final TransactionTemplate transaction;

    public Reviews(JdbcClient db, Annotations annotations, TransactionTemplate transaction) {
        this.db = db;
        this.annotations = annotations;
        this.transaction = transaction;
    }

    public record Review(String mutationId, String cardMutationId, int grade, Instant reviewedAt, String blobSha) {}

    public record Notebook(List<Annotations.Annotation> annotations, List<Review> reviews) {}

    /**
     * The user's anchored annotations and review log. LibrarySnapshotReader calls it inside the library's one
     * repeatable snapshot, so a concurrent delete cannot split cards from their logs.
     */
    public Notebook notebook(long userId) {
        return new Notebook(
                annotations.listAllForUser(userId),
                db.sql(SELECT + " ORDER BY r.reviewed_at, r.mutation_id")
                        .param("userId", userId)
                        .query(Review.class)
                        .list());
    }

    /**
     * Records a grade of the card {@code cardId} once per client {@code reviewMutationId}: a replay returns the stored
     * review, however the note changed since; the same id with other content is 409 MUTATION_ID_REUSED. The phone
     * decides whether a card can be graded in a version, since only it reads the bank question its Canonical line asks
     * (ADR-13); {@code blobSha} records the version answered. Holds the card's connection lock while it writes, so a
     * refresh or disconnect waits for it; a card that is not this user's, or was disconnected meanwhile, is 404.
     */
    Review grade(long userId, long cardId, UUID reviewMutationId, int grade, Instant reviewedAt, String blobSha) {
        return transaction.execute(tx -> {
            annotations.lockCard(userId, cardId);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            Instant storedAt =
                    reviewedAt.isBefore(Instant.EPOCH) ? Instant.EPOCH : reviewedAt.isAfter(now) ? now : reviewedAt;
            db.sql("""
                    INSERT INTO review_log(user_id, mutation_id, annotation_id, grade, reviewed_at, blob_sha, client_reviewed_at)
                    VALUES (:userId, :mutationId, :id, :grade, :at, :sha, :clientAt) ON CONFLICT DO NOTHING""")
                    .param("userId", userId)
                    .param("mutationId", reviewMutationId)
                    .param("id", cardId)
                    .param("grade", grade)
                    .param("at", Timestamp.from(storedAt))
                    .param("clientAt", reviewedAt.toString())
                    .param("sha", blobSha)
                    .update();
            var stored = db.sql(
                            "SELECT annotation_id, grade, reviewed_at, blob_sha, client_reviewed_at FROM review_log WHERE user_id = :userId AND mutation_id = :mutationId")
                    .param("userId", userId)
                    .param("mutationId", reviewMutationId)
                    .query(Stored.class)
                    .single();
            // Before V15 the accepted client timestamp was stored unchanged.
            String clientAt =
                    stored.clientReviewedAt() == null ? stored.reviewedAt().toString() : stored.clientReviewedAt();
            if (stored.annotationId() != cardId
                    || stored.grade() != grade
                    || !clientAt.equals(reviewedAt.toString())
                    || !stored.blobSha().equals(blobSha)) {
                throw new ApiFailure(Code.MUTATION_ID_REUSED, "This mutationId belongs to a different review.");
            }
            return db.sql(SELECT + " AND r.mutation_id = :mutationId")
                    .param("userId", userId)
                    .param("mutationId", reviewMutationId)
                    .query(Review.class)
                    .single();
        });
    }

    private record Stored(
            long annotationId,
            int grade,
            Instant reviewedAt,
            String blobSha,
            @Nullable String clientReviewedAt) {}
}
