package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.document.Documents;
import com.reporead.document.MarkdownRenderer;
import com.reporead.document.NoteVersions;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Highlights and cards with optional notes. External-call ceilings: create and reattach 1 GitHub request (the selected
 * version's blob, to verify the anchor), or 0 when replaying a known mutation id; list 0 when every highlight is resolved
 * against the note's current version, else 1 (the current version) plus 1 per older version holding a pre-Stage-4
 * location; edit, delete, and card confirmation 0. Source Markdown is never written.
 */
@RestController
public class AnnotationController {
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationController.class);
    /** Metrics: anchored annotations created (not replays); re-anchoring outcomes (method or ORPHANED) and the time each resolution took. */
    static final String CREATED = "reporead.annotations.created";
    static final String REANCHOR = "reporead.annotation.reanchor";
    static final String REANCHOR_DURATION = "reporead.annotation.reanchor.duration";
    static final int MAX_TEXT_CHARS = 10_000;
    private final Annotations annotations;
    private final Documents documents;
    private final NoteVersions noteVersions;
    private final MeterRegistry meters;
    /**
     * Serializes mutation fingerprints. Its own mapper, not the application's: a change to the shared JSON settings
     * would change every hash, and offline replays of already-recorded mutations would then be refused as reused.
     */
    private static final JsonMapper FINGERPRINT_JSON = JsonMapper.builder().build();

    public AnnotationController(Annotations annotations, Documents documents, NoteVersions noteVersions, MeterRegistry meters) {
        this.meters = meters;
        this.annotations = annotations;
        this.documents = documents;
        this.noteVersions = noteVersions;
    }

    record AnnotationList(List<Annotations.Annotation> annotations) {}

    @GetMapping("/api/documents/{id}/annotations")
    AnnotationList list(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        var document = documents.find(user.id(), id).orElseThrow(AnnotationController::notFound);
        var all = annotations.list(user.id(), id);
        // A deleted note has no current version; its highlights keep their last resolution.
        if (!document.deleted() && bringUpToDate(user, document, all)) all = annotations.list(user.id(), id);
        return new AnnotationList(all);
    }

    /**
     * Resolves every highlight against the note's current version. A pre-Stage-4 location first gets its
     * distinguishability from its own version. Returns whether anything needed resolving.
     */
    private boolean bringUpToDate(AppUser user, Documents.Located document, List<Annotations.Annotation> all) {
        String current = document.blobSha();
        var stale = all.stream().filter(annotation -> !annotation.resolvedBlobSha().equals(current)
            || annotation.location().rivalContext() == null).toList();
        if (stale.isEmpty()) return false;
        var versions = new HashMap<String, List<MarkdownRenderer.Block>>();
        Function<String, List<MarkdownRenderer.Block>> blocks =
            sha -> versions.computeIfAbsent(sha, version -> noteVersions.render(user, document, version).note().blocks());
        for (var annotation : stale) {
            var location = annotation.location();
            if (location.rivalContext() == null) {
                location = withEvidence(location, blocks.apply(location.sourceBlobSha()), annotation.id());
                annotations.fillEvidence(annotation.id(), location);
            }
            if (annotation.resolvedBlobSha().equals(current)) continue;
            var currentBlocks = blocks.apply(current);
            var from = location;
            var resolved = meters.timer(REANCHOR_DURATION).record(() -> Anchoring.resolve(from, current, currentBlocks));
            boolean applied = annotations.resolved(annotation, current, resolved.map(Anchoring.Resolved::anchor));
            String outcome = resolved.map(found -> found.method().name()).orElse("ORPHANED");
            // Counted once per applied resolution; a concurrent listing that lost the race changed nothing.
            if (applied) meters.counter(REANCHOR, "outcome", outcome).increment();
            LOG.info("Highlight resolved; userId={} documentId={} annotationId={} from={} to={} outcome={} applied={}", user.id(),
                document.id(), annotation.id(), location.sourceBlobSha(), current, outcome, applied);
        }
        return true;
    }

    /** A location recomputed in its own version, which must still contain it exactly. */
    private static Annotations.Anchor withEvidence(Annotations.Anchor location, List<MarkdownRenderer.Block> own, long annotationId) {
        var block = own.stream().filter(candidate -> candidate.id().equals(location.blockId())).findFirst()
            .filter(candidate -> candidate.text().length() >= location.endOffset()
                && candidate.text().substring(location.startOffset(), location.endOffset()).equals(location.exactText()))
            .orElseThrow(() -> new IllegalStateException("Annotation " + annotationId + " location " + location.blockId() + "["
                + location.startOffset() + "," + location.endOffset() + ") is not in its own version " + location.sourceBlobSha()));
        return Anchoring.anchorAt(location.sourceBlobSha(), own, block, location.startOffset(), location.endOffset());
    }

    /** What the client selected; the server verifies it and derives everything else from the canonical text. */
    record Selection(String sourceBlobSha, String blockId, Integer startOffset, Integer endOffset, String exactText) {}
    record CreateRequest(String mutationId, Selection anchor, String note, String type, String question) {}
    /** The content a mutation id is bound to. */
    record Fingerprint(long documentId, Selection anchor, String note) {}
    record CardFingerprint(long documentId, Selection anchor, String note, String type, String question) {}

    /** SHA-256 of the request content a mutation id is bound to; highlights keep their pre-P4 fingerprint shape. */
    static byte[] fingerprint(long documentId, Selection anchor, String note, String type, String question) {
        return sha256(FINGERPRINT_JSON.writeValueAsBytes(type.equals("CARD")
            ? new CardFingerprint(documentId, anchor, note, type, question) : new Fingerprint(documentId, anchor, note)));
    }

    @PostMapping("/api/documents/{id}/annotations")
    ResponseEntity<Annotations.Annotation> create(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody CreateRequest body) {
        UUID mutationId = mutationId(body.mutationId());
        var selection = body.anchor();
        String type = body.type() == null ? "HIGHLIGHT" : body.type();
        if (!(type.equals("HIGHLIGHT") || type.equals("CARD"))
            || (type.equals("CARD") ? body.question() == null || body.question().isBlank() || !validNote(body.question()) : body.question() != null)
            || !validSelection(selection) || !validNote(body.note())) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_ANNOTATION",
                "An annotation needs a source blob SHA, a block id, a non-empty UTF-16 range matching exactText (at most " + MAX_TEXT_CHARS
                    + " characters), an optional note of at most " + MAX_TEXT_CHARS + " characters, and a non-blank question for type CARD.");
        }
        var document = documents.find(user.id(), id).orElseThrow(AnnotationController::notFound);
        byte[] hash = fingerprint(id, selection, body.note(), type, body.question());
        var replayed = annotations.replay(user.id(), mutationId, hash);
        if (replayed.isPresent()) {
            LOG.info("Annotation creation replayed; userId={} annotationId={}", user.id(), replayed.get().id());
            return ResponseEntity.ok(replayed.get());
        }
        var anchor = verifiedAnchor(selection, noteVersions.render(user, document, selection.sourceBlobSha()).note());
        var created = annotations.create(user.id(), id, mutationId, hash, anchor, body.note(), type, body.question());
        if (!created.replayed()) meters.counter(CREATED).increment();
        LOG.info("Annotation {}; userId={} documentId={} annotationId={}", created.replayed() ? "creation replayed" : "created",
            user.id(), id, created.annotation().id());
        return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(created.annotation());
    }

    record ReattachRequest(Integer expectedVersion, Selection anchor) {}

    /** The user places an orphaned (or any) highlight on a selection they made in a version of its note. */
    @PostMapping("/api/annotations/{id}/reattach")
    Annotations.Annotation reattach(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody ReattachRequest body) {
        if (body.expectedVersion() == null || body.expectedVersion() < 1 || !validSelection(body.anchor())) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_ANNOTATION",
                "A reattachment needs expectedVersion and a selection: source blob SHA, block id, and a non-empty UTF-16 range matching exactText (at most "
                    + MAX_TEXT_CHARS + " characters).");
        }
        var annotation = annotations.find(user.id(), id).orElseThrow(AnnotationController::notFound);
        var document = documents.find(user.id(), annotation.documentId()).orElseThrow(() -> new IllegalStateException(
            "Annotation " + id + " of user " + user.id() + " references document " + annotation.documentId() + " outside their connections"));
        var location = verifiedAnchor(body.anchor(), noteVersions.render(user, document, body.anchor().sourceBlobSha()).note());
        var reattached = annotations.reattach(user.id(), id, body.expectedVersion(), location);
        LOG.info("Highlight reattached; userId={} documentId={} annotationId={} blobSha={} version={}", user.id(), document.id(), id,
            location.sourceBlobSha(), reattached.version());
        return reattached;
    }

    record CheckRequest(Integer expectedVersion, String blobSha) {}

    @PostMapping("/api/annotations/{id}/check")
    Annotations.Annotation checkCard(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody CheckRequest body) {
        if (body.expectedVersion() == null || body.expectedVersion() < 1 || body.blobSha() == null || !body.blobSha().matches("[0-9a-f]{40}")) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_ANNOTATION", "Card confirmation needs expectedVersion and a blob SHA.");
        }
        var checked = annotations.checkCard(user.id(), id, body.expectedVersion(), body.blobSha());
        LOG.info("Card checked; userId={} annotationId={} blobSha={}", user.id(), id, body.blobSha());
        return checked;
    }

    record EditRequest(String note, Integer expectedVersion) {}

    @PatchMapping("/api/annotations/{id}")
    Annotations.Annotation edit(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody EditRequest body) {
        if (body.expectedVersion() == null || body.expectedVersion() < 1 || !validNote(body.note())) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_ANNOTATION", "An edit needs expectedVersion and a note of at most " + MAX_TEXT_CHARS + " characters (or null).");
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
        return Anchoring.anchorAt(selection.sourceBlobSha(), rendered.blocks(), block, selection.startOffset(), selection.endOffset());
    }

    private static UUID mutationId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value);
        } catch (IllegalArgumentException error) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_ANNOTATION", "mutationId must be a client-generated UUID.");
        }
    }

    private static boolean validSelection(Selection selection) {
        return selection != null && selection.sourceBlobSha() != null && selection.sourceBlobSha().matches("[0-9a-f]{40}")
            && selection.blockId() != null && selection.blockId().matches("b[0-9]{1,4}")
            && selection.startOffset() != null && selection.endOffset() != null && selection.startOffset() >= 0
            && selection.endOffset() > selection.startOffset() && selection.exactText() != null
            && selection.exactText().length() == selection.endOffset() - selection.startOffset()
            && selection.exactText().length() <= MAX_TEXT_CHARS;
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
