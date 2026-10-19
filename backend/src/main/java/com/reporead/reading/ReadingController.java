// Owns reading-state HTTP input validation; ReadingStates owns persistence and the complete response shape.

package com.reporead.reading;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.Hashes;
import com.reporead.StoredText;
import com.reporead.auth.AppUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * No GitHub calls. Only the reader writes reading state, so refreshing a repository never changes what was last read.
 */
@RestController
public class ReadingController {
    private final ReadingStates states;

    public ReadingController(ReadingStates states) {
        this.states = states;
    }

    /** Input may omit textPrefix; the separate ReadingStates.Anchor response always publishes its nullable key. */
    record AnchorRequest(
            List<String> headingPath,

            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable
            String textPrefix,

            Integer blockIndex) {}

    /** Owns the accepted save body, not the persisted state returned after last-write-wins resolution. */
    record SaveRequest(String lastReadBlobSha, Integer progressPercent, AnchorRequest anchor, Instant lastReadAt) {}

    @PutMapping("/api/documents/{id}/reading-state")
    ReadingStates.State save(
            @AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody SaveRequest body) {
        validate(body);
        var anchor = body.anchor();
        Instant now = Instant.now();
        Instant lastReadAt = body.lastReadAt();
        if (lastReadAt.isBefore(Instant.EPOCH)) lastReadAt = Instant.EPOCH;
        else if (lastReadAt.isAfter(now)) lastReadAt = now;
        return states.save(
                user.id(),
                id,
                body.lastReadBlobSha(),
                body.progressPercent(),
                new ReadingStates.Anchor(anchor.headingPath(), anchor.textPrefix(), anchor.blockIndex()),
                lastReadAt);
    }

    private static void validate(SaveRequest body) {
        var anchor = body.anchor();
        boolean valid = Hashes.isGitSha(body.lastReadBlobSha())
                && body.progressPercent() != null
                && body.progressPercent() >= 0
                && body.progressPercent() <= 100
                && body.lastReadAt() != null
                && anchor != null
                && anchor.headingPath() != null
                && anchor.headingPath().stream().allMatch(heading -> heading != null && StoredText.storable(heading))
                && (anchor.textPrefix() == null || StoredText.storable(anchor.textPrefix()))
                && anchor.blockIndex() != null;
        if (!valid) {
            throw new ApiFailure(
                    Code.INVALID_REQUEST,
                    "Reading state requires a blob SHA, progress 0-100, lastReadAt, and an anchor with a string heading path and integer block index; anchor text cannot contain NUL or an unpaired surrogate.");
        }
    }
}
