package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.document.Documents;
import com.reporead.document.MarkdownRenderer;
import com.reporead.document.NoteVersions;
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
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * Highlights with optional notes. External-call ceilings: create 1 GitHub request (the source version's blob, to verify
 * the anchor) or 0 when replaying a known mutation id; list, edit, and delete 0. Source Markdown is never written.
 */
@RestController
public class AnnotationController {
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationController.class);
    static final int MAX_TEXT_CHARS = 10_000;
    static final int CONTEXT_CHARS = 32;
    private final Annotations annotations;
    private final Documents documents;
    private final NoteVersions noteVersions;
    private final JsonMapper json;

    public AnnotationController(Annotations annotations, Documents documents, NoteVersions noteVersions, JsonMapper json) {
        this.annotations = annotations;
        this.documents = documents;
        this.noteVersions = noteVersions;
        this.json = json;
    }

    record AnnotationList(List<Annotations.Annotation> annotations) {}

    @GetMapping("/api/documents/{id}/annotations")
    AnnotationList list(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        documents.find(user.id(), id).orElseThrow(AnnotationController::notFound);
        return new AnnotationList(annotations.list(user.id(), id));
    }

    /** What the client selected; the server verifies it and derives everything else from the canonical text. */
    record Selection(String sourceBlobSha, String blockId, Integer startOffset, Integer endOffset, String exactText) {}
    record CreateRequest(String mutationId, Selection anchor, String note) {}
    /** The content a mutation id is bound to. */
    private record Fingerprint(long documentId, Selection anchor, String note) {}

    @PostMapping("/api/documents/{id}/annotations")
    ResponseEntity<Annotations.Annotation> create(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody CreateRequest body) {
        UUID mutationId = mutationId(body.mutationId());
        var selection = body.anchor();
        boolean valid = selection != null && selection.sourceBlobSha() != null && selection.sourceBlobSha().matches("[0-9a-f]{40}")
            && selection.blockId() != null && selection.blockId().matches("b[0-9]{1,4}")
            && selection.startOffset() != null && selection.endOffset() != null && selection.startOffset() >= 0
            && selection.endOffset() > selection.startOffset() && selection.exactText() != null
            && selection.exactText().length() == selection.endOffset() - selection.startOffset()
            && selection.exactText().length() <= MAX_TEXT_CHARS && validNote(body.note());
        if (!valid) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_ANNOTATION",
                "An annotation needs a source blob SHA, a block id, a non-empty UTF-16 range matching exactText (at most 10000 characters), and an optional note of at most 10000 characters.");
        }
        var document = documents.find(user.id(), id).orElseThrow(AnnotationController::notFound);
        byte[] hash = sha256(json.writeValueAsBytes(new Fingerprint(id, selection, body.note())));
        var replayed = annotations.replay(user.id(), mutationId, hash);
        if (replayed.isPresent()) {
            LOG.info("Annotation creation replayed; userId={} annotationId={}", user.id(), replayed.get().id());
            return ResponseEntity.ok(replayed.get());
        }
        var anchor = verifiedAnchor(selection, noteVersions.render(user, document, selection.sourceBlobSha()).note());
        var created = annotations.create(user.id(), id, mutationId, hash, anchor, body.note());
        LOG.info("Annotation {}; userId={} documentId={} annotationId={}", created.replayed() ? "creation replayed" : "created",
            user.id(), id, created.annotation().id());
        return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(created.annotation());
    }

    record EditRequest(String note, Integer expectedVersion) {}

    @PatchMapping("/api/annotations/{id}")
    Annotations.Annotation edit(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody EditRequest body) {
        if (body.expectedVersion() == null || body.expectedVersion() < 1 || !validNote(body.note())) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_ANNOTATION", "An edit needs expectedVersion and a note of at most 10000 characters (or null).");
        }
        return annotations.updateNote(user.id(), id, body.expectedVersion(), body.note());
    }

    @DeleteMapping("/api/annotations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestParam int expectedVersion) {
        annotations.delete(user.id(), id, expectedVersion);
        LOG.info("Annotation deleted; userId={} annotationId={}", user.id(), id);
    }

    /** The selection must name real text in that exact version; context and headings come from the server's render. */
    static Annotations.Anchor verifiedAnchor(Selection selection, MarkdownRenderer.RenderedNote rendered) {
        var block = rendered.blocks().stream().filter(candidate -> candidate.id().equals(selection.blockId())).findFirst()
            .orElseThrow(() -> invalidAnchor("Block " + selection.blockId() + " does not exist in that version."));
        String text = block.text();
        if (selection.endOffset() > text.length() || !text.substring(selection.startOffset(), selection.endOffset()).equals(selection.exactText())) {
            throw invalidAnchor("The selected text does not match that version of the note.");
        }
        return new Annotations.Anchor(selection.sourceBlobSha(), block.id(), selection.exactText(),
            text.substring(Math.max(0, selection.startOffset() - CONTEXT_CHARS), selection.startOffset()),
            text.substring(selection.endOffset(), Math.min(text.length(), selection.endOffset() + CONTEXT_CHARS)),
            selection.startOffset(), selection.endOffset(), block.headingPath());
    }

    private static UUID mutationId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value);
        } catch (IllegalArgumentException error) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_ANNOTATION", "mutationId must be a client-generated UUID.");
        }
    }

    private static boolean validNote(String note) {
        return note == null || note.length() <= MAX_TEXT_CHARS;
    }

    private static ApiFailure invalidAnchor(String message) {
        return new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_ANCHOR", message);
    }

    private static ApiFailure notFound() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("AnnotationController requires the JDK SHA-256 implementation", error);
        }
    }
}
