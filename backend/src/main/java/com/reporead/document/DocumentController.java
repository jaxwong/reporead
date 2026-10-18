package com.reporead.document;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.Hashes;
import com.reporead.auth.AppUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * External-call ceilings: content 1 (the note's blob); image 1 (the file at the note's commit); changes 2 (the two
 * versions' blobs), 0 when they are the same version.
 */
@RestController
public class DocumentController {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentController.class);
    private final Documents documents;
    private final NoteVersions noteVersions;
    private final Attachments attachments;
    private final TransactionTemplate snapshot;

    public DocumentController(
            Documents documents, NoteVersions noteVersions, Attachments attachments, TransactionTemplate transaction) {
        this.documents = documents;
        this.noteVersions = noteVersions;
        this.attachments = attachments;
        this.snapshot = new TransactionTemplate(transaction.getTransactionManager());
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    /**
     * {@code text}: the canonical text of the note's blocks, one per line, for search on the phone.
     * {@code renderFormat}: MarkdownRenderer.FORMAT, so the app can refresh saved copies made in an older format.
     */
    record Content(
            long documentId,
            String path,
            String title,
            String sourceBlobSha,
            String snapshotCommitSha,
            String html,
            String text,
            int renderFormat) {
        Content {
            long bytes = List.of(path, title, sourceBlobSha, snapshotCommitSha, html, text).stream()
                    .mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length)
                    .sum();
            if (bytes > MarkdownRenderer.MAX_PAGE_BYTES) {
                throw new ApiFailure(
                        Code.UNSUPPORTED_CONTENT,
                        "Rendered note exceeds the " + MarkdownRenderer.MAX_PAGE_BYTES
                                + "-byte saved-page limit (HTML, search text and metadata).");
            }
        }
    }

    private record ContentSource(Documents.Located document, List<String> imagePaths) {}

    @GetMapping("/api/documents/{id}/content")
    Content content(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        // No GitHub I/O inside the transaction: a refresh cannot mix one checkpoint with another image index.
        var source = snapshot.execute(status -> {
            var document = present(user, id);
            return new ContentSource(document, attachments.paths(document.connectionId()));
        });
        var document = source.document();
        var version = noteVersions.render(user, document, document.blobSha());
        var rendered = version.note();
        Attachments.bind(rendered.page(), document.path(), source.imagePaths());
        String html;
        try {
            html = MarkdownRenderer.pageHtml(rendered.page());
        } catch (MarkdownRenderer.ContentRejected rejected) {
            throw new ApiFailure(Code.UNSUPPORTED_CONTENT, rejected.getMessage());
        }
        LOG.info(
                "Document rendered; userId={} documentId={} blobSha={} snapshotCommitSha={} bytes={} blocks={} diagrams={}",
                user.id(),
                id,
                document.blobSha(),
                document.commitSha(),
                version.bytes(),
                rendered.blocks().size(),
                rendered.diagramCount());
        return new Content(
                id,
                document.path(),
                document.title(),
                document.blobSha(),
                document.commitSha(),
                html,
                String.join(
                        "\n",
                        rendered.blocks().stream()
                                .map(MarkdownRenderer.Block::text)
                                .toList()),
                MarkdownRenderer.FORMAT);
    }

    /** An owned note that was in the latest complete repository refresh. */
    private Documents.Located present(AppUser user, long id) {
        var document = documents.find(user.id(), id).orElseThrow(ApiFailure::notFound);
        if (document.deleted()) {
            throw new ApiFailure(
                    Code.DOCUMENT_DELETED, "This note was not present in the latest complete repository refresh.");
        }
        return document;
    }

    enum ChangeStatus {
        CHANGED,
        UNCHANGED,
        SINCE_UNAVAILABLE,
        TOO_LARGE
    }

    /**
     * {@code reason} says why the older version is unavailable; {@code sections} is empty unless the status is CHANGED.
     */
    record Changes(
            long documentId,
            String sinceBlobSha,
            String toBlobSha,
            ChangeStatus status,
            @Nullable String reason,
            int addedLines,
            int removedLines,
            List<NoteChanges.Section> sections) {
        static Changes changed(long documentId, String since, String to, NoteChanges.Comparison comparison) {
            return new Changes(
                    documentId,
                    since,
                    to,
                    ChangeStatus.CHANGED,
                    null,
                    comparison.addedLines(),
                    comparison.removedLines(),
                    comparison.sections());
        }

        static Changes unchanged(long documentId, String since, String to) {
            return new Changes(documentId, since, to, ChangeStatus.UNCHANGED, null, 0, 0, List.of());
        }

        static Changes sinceUnavailable(long documentId, String since, String to, String reason) {
            return new Changes(documentId, since, to, ChangeStatus.SINCE_UNAVAILABLE, reason, 0, 0, List.of());
        }

        static Changes tooLarge(long documentId, String since, String to) {
            return new Changes(documentId, since, to, ChangeStatus.TOO_LARGE, null, 0, 0, List.of());
        }
    }

    /**
     * Which sections changed between {@code since}, the version the user last read, and {@code to}, the version on
     * their screen, whose heading blocks the sections name. The phone supplies both: its last-read version can be newer
     * than the server's until its pending reading saves are sent. A version GitHub no longer has, or can no longer be
     * read as a note, makes the comparison unavailable; any other failure fails the request.
     */
    @GetMapping("/api/documents/{id}/changes")
    Changes changes(
            @AuthenticationPrincipal AppUser user,
            @PathVariable long id,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String to) {
        if (!Hashes.isGitSha(since) || !Hashes.isGitSha(to)) {
            throw new ApiFailure(Code.INVALID_VERSIONS, "since and to must be lowercase 40-character blob SHAs.");
        }
        var document = present(user, id);
        Changes result;
        if (since.equals(to)) {
            result = Changes.unchanged(id, since, to);
        } else {
            result = compare(user, document, since, to);
        }
        LOG.info(
                "Changes compared; userId={} documentId={} since={} to={} status={} sections={} added={} removed={}",
                user.id(),
                id,
                since,
                to,
                result.status(),
                result.sections().size(),
                result.addedLines(),
                result.removedLines());
        return result;
    }

    private Changes compare(AppUser user, Documents.Located document, String since, String to) {
        NoteVersions.Rendered old;
        try {
            old = noteVersions.render(user, document, since);
        } catch (ApiFailure failure) {
            if (!NoteVersions.versionUnavailable(failure)) throw failure;
            return Changes.sinceUnavailable(document.id(), since, to, failure.getMessage());
        }
        return NoteChanges.compare(old, noteVersions.render(user, document, to))
                .map(comparison -> Changes.changed(document.id(), since, to, comparison))
                .orElseGet(() -> Changes.tooLarge(document.id(), since, to));
    }

    /**
     * An image in an owned note's repository, fetched at the displayed page's explicit commit and resolved path. Access
     * is per repository, not per reference: the note need not name the image, as the user's own GitHub token reads the
     * whole repository anyway. Not cached on the server.
     */
    @GetMapping("/api/documents/{id}/image")
    ResponseEntity<byte[]> image(
            @AuthenticationPrincipal AppUser user,
            @PathVariable long id,
            @RequestParam String path,
            @RequestParam String commitSha) {
        if (!Hashes.isGitSha(commitSha)) {
            throw new ApiFailure(Code.INVALID_VERSIONS, "commitSha must be the saved page's lowercase Git SHA-1.");
        }
        if (!MarkdownRenderer.isImagePath(path)) {
            throw new ApiFailure(
                    Code.INVALID_IMAGE_PATH, "path must name a normalized png, jpg, gif, webp, or svg image.");
        }
        var document = present(user, id);
        byte[] bytes = attachments.image(user, document, path, commitSha);
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        LOG.info(
                "Document image served; userId={} documentId={} snapshotCommitSha={} bytes={}",
                user.id(),
                id,
                commitSha,
                bytes.length);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MarkdownRenderer.IMAGE_TYPES.get(extension)))
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }
}
