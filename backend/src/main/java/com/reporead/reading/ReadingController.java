package com.reporead.reading;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.ClientClock;
import com.reporead.Hashes;
import com.reporead.auth.AppUser;
import com.reporead.document.MarkdownRenderer;
import java.time.Instant;
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
    private static final int MAX_HEADINGS = 6;
    private static final int MAX_PREFIX_CHARS = 200;
    /** Exactly what rendered pages carry: their heading paths and block indexes. */
    private static final int MAX_HEADING_CHARS = MarkdownRenderer.MAX_HEADING_CHARS;

    private static final int MAX_BLOCK_INDEX = MarkdownRenderer.MAX_BLOCKS - 1;
    private final ReadingStates states;

    public ReadingController(ReadingStates states) {
        this.states = states;
    }

    record SaveRequest(
            String lastReadBlobSha, Integer progressPercent, ReadingStates.Anchor anchor, Instant lastReadAt) {}

    @PutMapping("/api/documents/{id}/reading-state")
    ReadingStates.State save(
            @AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody SaveRequest body) {
        validate(body);
        return states.save(
                user.id(), id, body.lastReadBlobSha(), body.progressPercent(), body.anchor(), body.lastReadAt());
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
                && anchor.headingPath().size() <= MAX_HEADINGS
                && anchor.headingPath().stream()
                        .allMatch(heading -> heading != null && heading.length() <= MAX_HEADING_CHARS)
                && (anchor.textPrefix() == null || anchor.textPrefix().length() <= MAX_PREFIX_CHARS)
                && anchor.blockIndex() != null
                && anchor.blockIndex() >= 0
                && anchor.blockIndex() <= MAX_BLOCK_INDEX;
        if (!valid) {
            throw new ApiFailure(
                    Code.INVALID_READING_STATE,
                    "Reading state requires a blob SHA, progress 0-100, lastReadAt, and an anchor with at most "
                            + MAX_HEADINGS + " headings of " + MAX_HEADING_CHARS + " characters, a prefix of at most "
                            + MAX_PREFIX_CHARS + " characters, and a block index 0-" + MAX_BLOCK_INDEX + ".");
        }
        if (!ClientClock.accepts(body.lastReadAt())) {
            throw new ApiFailure(
                    Code.INVALID_READING_STATE,
                    "lastReadAt must be from 1970 through " + ClientClock.MAX_AHEAD.toMinutes()
                            + " minutes ahead of the server; the client's clock may be ahead.");
        }
    }
}
