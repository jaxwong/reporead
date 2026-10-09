package com.reporead.reading;

import com.reporead.ApiFailure;
import com.reporead.ClientClock;
import com.reporead.auth.AppUser;
import com.reporead.document.Documents;
import com.reporead.document.MarkdownRenderer;
import com.reporead.repository.RepositoryConnections;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** No GitHub calls. Only the reader writes reading state, so refreshing a repository never changes what was last read. */
@RestController
public class ReadingController {
    private static final int MAX_HEADINGS = 6;
    private static final int MAX_PREFIX_CHARS = 200;
    /** Exactly what rendered pages carry: their heading paths and block indexes. */
    private static final int MAX_HEADING_CHARS = MarkdownRenderer.MAX_HEADING_CHARS;
    private static final int MAX_BLOCK_INDEX = MarkdownRenderer.MAX_BLOCKS - 1;
    private final ReadingStates states;
    private final Documents documents;
    private final RepositoryConnections connections;
    private final TransactionTemplate transaction;

    public ReadingController(ReadingStates states, Documents documents, RepositoryConnections connections, TransactionTemplate transaction) {
        this.states = states;
        this.documents = documents;
        this.connections = connections;
        this.transaction = transaction;
    }

    record ReadingStateList(List<ReadingStates.State> readingStates) {}

    @GetMapping("/api/reading-states")
    ReadingStateList list(@AuthenticationPrincipal AppUser user) {
        return new ReadingStateList(states.list(user.id()));
    }

    record SaveRequest(String lastReadBlobSha, Integer progressPercent, ReadingStates.Anchor anchor, Instant lastReadAt) {}

    @PutMapping("/api/documents/{id}/reading-state")
    ReadingStates.State save(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody SaveRequest body) {
        validate(body);
        documents.find(user.id(), id).orElseThrow(() -> new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found."));
        return transaction.execute(status -> {
            connections.lockForDocumentWrite(user.id(), id);
            return states.save(user.id(), id, body.lastReadBlobSha(), body.progressPercent(), body.anchor(), body.lastReadAt());
        });
    }

    private static void validate(SaveRequest body) {
        var anchor = body.anchor();
        boolean valid = body.lastReadBlobSha() != null && body.lastReadBlobSha().matches("[0-9a-f]{40}")
            && body.progressPercent() != null && body.progressPercent() >= 0 && body.progressPercent() <= 100
            && body.lastReadAt() != null
            && anchor != null && anchor.headingPath() != null && anchor.headingPath().size() <= MAX_HEADINGS
            && anchor.headingPath().stream().allMatch(heading -> heading != null && heading.length() <= MAX_HEADING_CHARS)
            && (anchor.textPrefix() == null || anchor.textPrefix().length() <= MAX_PREFIX_CHARS)
            && anchor.blockIndex() != null && anchor.blockIndex() >= 0 && anchor.blockIndex() <= MAX_BLOCK_INDEX;
        if (!valid) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_READING_STATE",
                "Reading state requires a blob SHA, progress 0-100, lastReadAt, and an anchor with at most " + MAX_HEADINGS + " headings of "
                    + MAX_HEADING_CHARS + " characters, a prefix of at most " + MAX_PREFIX_CHARS + " characters, and a block index 0-" + MAX_BLOCK_INDEX + ".");
        }
        if (body.lastReadAt().isAfter(Instant.now().plus(ClientClock.MAX_AHEAD))) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_READING_STATE",
                "lastReadAt is more than " + ClientClock.MAX_AHEAD.toMinutes() + " minutes in the future; check the phone's clock.");
        }
    }
}
