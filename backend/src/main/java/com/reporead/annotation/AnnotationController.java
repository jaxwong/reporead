package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.Hashes;
import com.reporead.annotation.AnnotationService.Selection;
import com.reporead.auth.AppUser;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Highlights and cards with optional notes. External-call ceilings are AnnotationService's for list, create and
 * reattach; edit, delete, and card confirmation make none. Source Markdown is never written.
 */
@RestController
public class AnnotationController {
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationController.class);
    static final int MAX_TEXT_CHARS = 10_000;
    private final AnnotationService service;
    private final Annotations annotations;

    public AnnotationController(AnnotationService service, Annotations annotations) {
        this.service = service;
        this.annotations = annotations;
    }

    record AnnotationList(List<Annotations.Annotation> annotations) {}

    @GetMapping("/api/documents/{id}/annotations")
    AnnotationList list(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return new AnnotationList(service.list(user, id));
    }

    record CreateRequest(String mutationId, Selection anchor, String note, String type, String question) {}

    @PostMapping("/api/documents/{id}/annotations")
    ResponseEntity<Annotations.Annotation> create(
            @AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody CreateRequest body) {
        UUID mutationId = mutationId(body.mutationId());
        var selection = body.anchor();
        String requested = body.type() == null ? "HIGHLIGHT" : body.type();
        boolean card = requested.equals("CARD");
        if (!(requested.equals("HIGHLIGHT") || card)
                || (card
                        ? body.question() == null || body.question().isBlank() || !validNote(body.question())
                        : body.question() != null)
                || !validSelection(selection)
                || !validNote(body.note())) {
            throw new ApiFailure(
                    Code.INVALID_ANNOTATION,
                    "An annotation needs a source blob SHA, a block id, a non-empty UTF-16 range matching exactText (at most "
                            + MAX_TEXT_CHARS + " characters), an optional note of at most " + MAX_TEXT_CHARS
                            + " characters, and a non-blank question for type CARD; a note or question cannot contain"
                            + " NUL or an unpaired surrogate.");
        }
        var type = card ? Annotations.Type.CARD : Annotations.Type.HIGHLIGHT;
        var created = service.create(user, id, mutationId, selection, body.note(), type, body.question());
        return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(created.annotation());
    }

    record ReattachRequest(Integer expectedVersion, Selection anchor) {}

    /** The user places an orphaned (or any) highlight on a selection they made in a version of its note. */
    @PostMapping("/api/annotations/{id}/reattach")
    Annotations.Annotation reattach(
            @AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody ReattachRequest body) {
        if (body.expectedVersion() == null || body.expectedVersion() < 1 || !validSelection(body.anchor())) {
            throw new ApiFailure(
                    Code.INVALID_ANNOTATION,
                    "A reattachment needs expectedVersion and a selection: source blob SHA, block id, and a non-empty UTF-16 range matching exactText (at most "
                            + MAX_TEXT_CHARS + " characters).");
        }
        return service.reattach(user, id, body.expectedVersion(), body.anchor());
    }

    record CheckRequest(Integer expectedVersion, String blobSha) {}

    @PostMapping("/api/annotations/{id}/check")
    Annotations.Annotation checkCard(
            @AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody CheckRequest body) {
        if (body.expectedVersion() == null || body.expectedVersion() < 1 || !Hashes.isGitSha(body.blobSha())) {
            throw new ApiFailure(Code.INVALID_ANNOTATION, "Card confirmation needs expectedVersion and a blob SHA.");
        }
        var checked = annotations.checkCard(user.id(), id, body.expectedVersion(), body.blobSha());
        LOG.info("Card checked; userId={} annotationId={} blobSha={}", user.id(), id, body.blobSha());
        return checked;
    }

    record EditRequest(String note, Integer expectedVersion) {}

    @PatchMapping("/api/annotations/{id}")
    Annotations.Annotation edit(
            @AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody EditRequest body) {
        if (body.expectedVersion() == null || body.expectedVersion() < 1 || !validNote(body.note())) {
            throw new ApiFailure(
                    Code.INVALID_ANNOTATION,
                    "An edit needs expectedVersion and a note of at most " + MAX_TEXT_CHARS
                            + " characters with no NUL or unpaired surrogate (or null).");
        }
        return annotations.updateNote(user.id(), id, body.expectedVersion(), body.note());
    }

    @DeleteMapping("/api/annotations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestParam int expectedVersion) {
        annotations.delete(user.id(), id, expectedVersion);
        LOG.info("Annotation deleted; userId={} annotationId={}", user.id(), id);
    }

    private static UUID mutationId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value);
        } catch (IllegalArgumentException error) {
            throw new ApiFailure(Code.INVALID_ANNOTATION, "mutationId must be a client-generated UUID.");
        }
    }

    private static boolean validSelection(Selection selection) {
        return selection != null
                && Hashes.isGitSha(selection.sourceBlobSha())
                && selection.blockId() != null
                && selection.blockId().matches("b[0-9]{1,4}")
                && selection.startOffset() != null
                && selection.endOffset() != null
                && selection.startOffset() >= 0
                && selection.endOffset() > selection.startOffset()
                && selection.exactText() != null
                && selection.exactText().length() == selection.endOffset() - selection.startOffset()
                && selection.exactText().length() <= MAX_TEXT_CHARS;
    }

    /**
     * A note or question the database stores exactly: PostgreSQL refuses U+0000, and the driver would store an unpaired
     * surrogate as '?'. Refusing them here makes them this item's fault, which the phone marks instead of resending.
     */
    private static boolean validNote(String note) {
        return note == null
                || (note.length() <= MAX_TEXT_CHARS
                        && note.codePoints().noneMatch(c -> c == 0 || Character.getType(c) == Character.SURROGATE));
    }
}
