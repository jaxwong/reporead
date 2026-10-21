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
    private static final int SESSION_LIMIT = 40;
    private static final String SELECT = """
        select r.mutation_id, a.mutation_id as card_mutation_id, r.grade, r.reviewed_at, r.blob_sha
        from review_log r join annotations a on a.id = r.annotation_id
        where r.user_id = :userId""";

    private final JdbcClient db;
    private final Annotations annotations;
    private final TransactionTemplate transaction;

    public Reviews(JdbcClient db, Annotations annotations, TransactionTemplate transaction) {
        this.db = db;
        this.annotations = annotations;
        this.transaction = transaction;
    }

    public record Review(String mutationId, String cardMutationId, int grade, Instant reviewedAt, String blobSha) {}

    public record Notebook(List<Annotations.Annotation> annotations, List<Review> reviews, int sessionLimit) {}

    /**
     * The user's anchored annotations and review log. Library.read calls it inside the library's one repeatable
     * snapshot, so a concurrent delete cannot split cards from their logs.
     */
    public Notebook notebook(long userId) {
        return new Notebook(
                annotations.notebook(userId),
                db.sql(SELECT + " order by r.reviewed_at, r.mutation_id")
                        .param("userId", userId)
                        .query(Review.class)
                        .list(),
                SESSION_LIMIT);
    }

    /**
     * Records a grade of the card {@code cardId} once per client {@code mutation}: a replay returns the stored review,
     * however the note changed since; the same id with other content is 409 MUTATION_ID_REUSED. The phone decides
     * whether a card can be graded in a version, since only it reads the bank question its Canonical line asks
     * (ADR-13); {@code blobSha} records the version answered. Holds the card's connection lock while it writes, so a
     * refresh or disconnect waits for it; a card that is not this user's, or was disconnected meanwhile, is 404.
     */
    Review grade(long userId, long cardId, UUID mutation, int grade, Instant reviewedAt, String blobSha) {
        return transaction.execute(tx -> {
            annotations.lockCard(userId, cardId);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            Instant storedAt =
                    reviewedAt.isBefore(Instant.EPOCH) ? Instant.EPOCH : reviewedAt.isAfter(now) ? now : reviewedAt;
            db.sql("""
                    insert into review_log(user_id, mutation_id, annotation_id, grade, reviewed_at, blob_sha, client_reviewed_at)
                    values (:userId, :mutation, :id, :grade, :at, :sha, :clientAt) on conflict do nothing""")
                    .param("userId", userId)
                    .param("mutation", mutation)
                    .param("id", cardId)
                    .param("grade", grade)
                    .param("at", Timestamp.from(storedAt))
                    .param("clientAt", reviewedAt.toString())
                    .param("sha", blobSha)
                    .update();
            var stored = db.sql(
                            "select annotation_id, grade, reviewed_at, blob_sha, client_reviewed_at from review_log where user_id = :userId and mutation_id = :mutation")
                    .param("userId", userId)
                    .param("mutation", mutation)
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
            return db.sql(SELECT + " and r.mutation_id = :mutation")
                    .param("userId", userId)
                    .param("mutation", mutation)
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
