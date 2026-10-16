package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.Hashes;
import com.reporead.auth.AppUser;
import com.reporead.document.Documents;
import com.reporead.document.MarkdownRenderer;
import com.reporead.document.NoteVersions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Highlights and cards against the note versions they belong to: creating one from a client's selection, verified in
 * that exact version and idempotent per client mutation id; reattaching one; and resolving a note's highlights against
 * its current version when they are listed (ADR-06). External-call ceilings: create and reattach 1 GitHub request (the
 * selected version's blob), or 0 when replaying a known mutation id; list 0 when every highlight is resolved against
 * the note's current version, else 1 (the current version).
 */
@Service
public class AnnotationService {
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationService.class);
    /**
     * Metrics: anchored annotations created (not replays); re-anchoring outcomes (method or ORPHANED) and the time each
     * resolution took.
     */
    static final String CREATED = "reporead.annotations.created";

    static final String REANCHOR = "reporead.annotation.reanchor";
    static final String REANCHOR_DURATION = "reporead.annotation.reanchor.duration";
    /**
     * Serializes mutation fingerprints. Its own mapper, not the application's: a change to the shared JSON settings
     * would change every hash, and offline replays of already-recorded mutations would then be refused as reused.
     */
    private static final JsonMapper FINGERPRINT_JSON = JsonMapper.builder().build();

    private final Annotations annotations;
    private final Documents documents;
    private final NoteVersions noteVersions;
    private final MeterRegistry meters;

    public AnnotationService(
            Annotations annotations, Documents documents, NoteVersions noteVersions, MeterRegistry meters) {
        this.annotations = annotations;
        this.documents = documents;
        this.noteVersions = noteVersions;
        this.meters = meters;
    }

    /** What the client selected; the server verifies it and derives everything else from the canonical text. */
    record Selection(String sourceBlobSha, String blockId, Integer startOffset, Integer endOffset, String exactText) {}

    /** The content a mutation id is bound to. */
    record Fingerprint(long documentId, Selection anchor, String note) {}

    record CardFingerprint(long documentId, Selection anchor, String note, Annotations.Type type, String question) {}

    /**
     * SHA-256 of the request content a mutation id is bound to. Highlights keep the shape they had before cards
     * existed, so their stored hashes still match a replay.
     */
    static byte[] fingerprint(long documentId, Selection anchor, String note, Annotations.Type type, String question) {
        return Hashes.sha256(FINGERPRINT_JSON.writeValueAsBytes(
                type == Annotations.Type.CARD
                        ? new CardFingerprint(documentId, anchor, note, type, question)
                        : new Fingerprint(documentId, anchor, note)));
    }

    /** The note's highlights and cards, each resolved against its current version first. */
    List<Annotations.Annotation> list(AppUser user, long documentId) {
        var document = documents.find(user.id(), documentId).orElseThrow(ApiFailure::notFound);
        var all = annotations.list(user.id(), documentId);
        // A deleted note has no current version; its highlights keep their last resolution.
        if (!document.deleted() && bringUpToDate(user, document, all)) all = annotations.list(user.id(), documentId);
        return all;
    }

    /** Resolves every highlight against the note's current version. Returns whether anything needed resolving. */
    private boolean bringUpToDate(AppUser user, Documents.Located document, List<Annotations.Annotation> all) {
        String current = document.blobSha();
        var stale = all.stream()
                .filter(annotation -> !annotation.resolvedBlobSha().equals(current))
                .toList();
        if (stale.isEmpty()) return false;
        var currentBlocks = noteVersions.render(user, document, current).note().blocks();
        for (var annotation : stale) {
            var from = annotation.location();
            var resolved =
                    meters.timer(REANCHOR_DURATION).record(() -> Anchoring.resolve(from, current, currentBlocks));
            boolean applied = annotations.resolved(annotation, current, resolved.map(Anchoring.Resolved::location));
            String outcome = resolved.map(found -> found.method().name()).orElse(Annotations.Status.ORPHANED.name());
            // Counted once per applied resolution; a concurrent listing that lost the race changed nothing.
            if (applied) meters.counter(REANCHOR, "outcome", outcome).increment();
            LOG.info(
                    "Highlight resolved; userId={} documentId={} annotationId={} from={} to={} outcome={} applied={}",
                    user.id(),
                    document.id(),
                    annotation.id(),
                    from.passage().sourceBlobSha(),
                    current,
                    outcome,
                    applied);
        }
        return true;
    }

    /**
     * Creates a highlight or card from a selection in one version of an owned note, or returns the earlier result of
     * the same mutation id ({@code replayed}). The same id with other content is 409, and one whose annotation was
     * since deleted is 410.
     */
    Annotations.Created create(
            AppUser user,
            long documentId,
            UUID mutationId,
            Selection selection,
            String note,
            Annotations.Type type,
            String question) {
        var document = documents.find(user.id(), documentId).orElseThrow(ApiFailure::notFound);
        byte[] hash = fingerprint(documentId, selection, note, type, question);
        var replayed = annotations.replay(user.id(), mutationId, hash);
        if (replayed.isPresent()) {
            LOG.info(
                    "Annotation creation replayed; userId={} annotationId={}",
                    user.id(),
                    replayed.get().id());
            return new Annotations.Created(replayed.get(), true);
        }
        var location = verifiedLocation(
                selection,
                noteVersions.render(user, document, selection.sourceBlobSha()).note());
        var created = annotations.create(user.id(), documentId, mutationId, hash, location, note, type, question);
        if (!created.replayed()) meters.counter(CREATED).increment();
        LOG.info(
                "Annotation {}; userId={} documentId={} annotationId={}",
                created.replayed() ? "creation replayed" : "created",
                user.id(),
                documentId,
                created.annotation().id());
        return created;
    }

    /** The user places an orphaned (or any) highlight on a selection they made in a version of its note. */
    Annotations.Annotation reattach(AppUser user, long id, int expectedVersion, Selection selection) {
        // A disconnect between the two reads deletes the annotation as well, so either one missing is 404.
        var document = annotations
                .find(user.id(), id)
                .flatMap(annotation -> documents.find(user.id(), annotation.documentId()))
                .orElseThrow(ApiFailure::notFound);
        var location = verifiedLocation(
                selection,
                noteVersions.render(user, document, selection.sourceBlobSha()).note());
        var reattached = annotations.reattach(user.id(), id, expectedVersion, location);
        LOG.info(
                "Highlight reattached; userId={} documentId={} annotationId={} blobSha={} version={}",
                user.id(),
                document.id(),
                id,
                location.passage().sourceBlobSha(),
                reattached.version());
        return reattached;
    }

    /** The selection must name real text in that exact version; context and headings come from the server's render. */
    static Annotations.Location verifiedLocation(Selection selection, MarkdownRenderer.RenderedNote rendered) {
        var block = rendered.blocks().stream()
                .filter(candidate -> candidate.id().equals(selection.blockId()))
                .findFirst()
                .orElseThrow(() -> invalidAnchor("Block " + selection.blockId() + " does not exist in that version."));
        String text = block.text();
        if (selection.endOffset() > text.length()
                || !text.substring(selection.startOffset(), selection.endOffset())
                        .equals(selection.exactText())) {
            throw invalidAnchor("The selected text does not match that version of the note.");
        }
        return Anchoring.anchorAt(
                selection.sourceBlobSha(), rendered.blocks(), block, selection.startOffset(), selection.endOffset());
    }

    private static ApiFailure invalidAnchor(String message) {
        return new ApiFailure(Code.INVALID_ANCHOR, message);
    }
}
