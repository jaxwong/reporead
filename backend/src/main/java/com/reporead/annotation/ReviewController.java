package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.Hashes;
import com.reporead.auth.AppUser;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The grading route; it makes zero external calls. The notebook is read as part of GET /api/library. */
@RestController
public class ReviewController {
    private static final Logger LOG = LoggerFactory.getLogger(ReviewController.class);
    private final Reviews reviews;

    public ReviewController(Reviews reviews) {
        this.reviews = reviews;
    }

    record GradeRequest(String mutationId, Integer grade, Instant reviewedAt, String blobSha) {}

    @PostMapping("/api/annotations/{id}/reviews")
    Reviews.Review grade(
            @AuthenticationPrincipal AppUser user, @PathVariable("id") long cardId, @RequestBody GradeRequest body) {
        UUID reviewMutationId;
        try {
            reviewMutationId = UUID.fromString(body.mutationId() == null ? "" : body.mutationId());
        } catch (IllegalArgumentException failure) {
            throw invalid("mutationId must be a UUID.");
        }
        if (body.grade() == null
                || body.grade() < 0
                || body.grade() > 5
                || body.reviewedAt() == null
                || body.reviewedAt().getNano() % 1_000_000 != 0
                || !Hashes.isGitSha(body.blobSha())) {
            throw invalid("A review needs grade 0–5, a millisecond reviewedAt, and the verified answer's blob SHA.");
        }
        var review =
                reviews.grade(user.id(), cardId, reviewMutationId, body.grade(), body.reviewedAt(), body.blobSha());
        LOG.info(
                "Review stored or replayed; userId={} annotationId={} mutationId={} grade={}",
                user.id(),
                cardId,
                reviewMutationId,
                body.grade());
        return review;
    }

    private static ApiFailure invalid(String message) {
        return new ApiFailure(Code.INVALID_REVIEW, message);
    }
}
