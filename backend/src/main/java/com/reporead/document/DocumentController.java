package com.reporead.document;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.Hashes;
import com.reporead.auth.AppUser;
import com.reporead.github.GitHubApi;
import com.reporead.repository.RepositoryConnections;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * External-call ceilings: listing 0 GitHub requests; content 1 (the note's blob); image 1 (the file at the note's
 * commit); changes 2 (the two versions' blobs), 0 when they are the same version.
 */
@RestController
public class DocumentController {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentController.class);
    private final RepositoryConnections connections;
    private final Documents documents;
    private final GitHubApi github;
    private final NoteVersions noteVersions;
    private final Attachments attachments;

    public DocumentController(
            RepositoryConnections connections,
            Documents documents,
            GitHubApi github,
            NoteVersions noteVersions,
            Attachments attachments) {
        this.attachments = attachments;
        this.connections = connections;
        this.documents = documents;
        this.github = github;
        this.noteVersions = noteVersions;
    }

    record DocumentList(long repositoryId, String lastSyncedCommitSha, List<Documents.Summary> documents) {}

    @GetMapping("/api/repositories/{id}/documents")
    DocumentList list(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        var connection = connections.find(user.id(), id).orElseThrow(ApiFailure::notFound);
        return new DocumentList(id, connection.lastSyncedCommitSha(), documents.list(id));
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
            String html,
            String text,
            int renderFormat) {}

    @GetMapping("/api/documents/{id}/content")
    Content content(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        var document = present(user, id);
        var version = noteVersions.render(user, document, document.blobSha());
        var rendered = version.note();
        LOG.info(
                "Document rendered; userId={} documentId={} blobSha={} bytes={} blocks={} diagrams={}",
                user.id(),
                id,
                document.blobSha(),
                version.bytes(),
                rendered.blocks().size(),
                rendered.diagramCount());
        return new Content(
                id,
                document.path(),
                document.title(),
                document.blobSha(),
                rendered.html(),
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
            String reason,
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
            if (failure.code != Code.SOURCE_NOT_FOUND && failure.code != Code.UNSUPPORTED_CONTENT) throw failure;
            return Changes.sinceUnavailable(document.id(), since, to, failure.getMessage());
        }
        var current = noteVersions.render(user, document, to);
        return NoteChanges.compare(
                        MarkdownRenderer.sourceLines(old.markdown()),
                        old.note().headings(),
                        MarkdownRenderer.sourceLines(current.markdown()),
                        current.note().headings())
                .map(comparison -> Changes.changed(document.id(), since, to, comparison))
                .orElseGet(() -> Changes.tooLarge(document.id(), since, to));
    }

    /**
     * A repository image referenced by an owned note, fetched at the note's current commit: by {@code path}, or by an
     * Obsidian {@code embed} name resolved against the latest snapshot's image files (no GitHub call when it is missing
     * or ambiguous). Not cached on the server.
     */
    @GetMapping("/api/documents/{id}/image")
    ResponseEntity<byte[]> image(
            @AuthenticationPrincipal AppUser user,
            @PathVariable long id,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String embed) {
        if ((path == null) == (embed == null)) {
            throw new ApiFailure(Code.INVALID_IMAGE_PATH, "Give exactly one of path or embed.");
        }
        if (path != null
                ? !MarkdownRenderer.isImagePath(path)
                : embed.length() > 1024 || !MarkdownRenderer.isImagePath(embed)) {
            throw new ApiFailure(
                    Code.INVALID_IMAGE_PATH, "path or embed must name a png, jpg, gif, webp, or svg image.");
        }
        var document = present(user, id);
        if (embed != null) path = attachments.resolve(document.connectionId(), document.path(), embed);
        String token = github.userToken(user.githubUserId());
        byte[] bytes = github.imageFile(token, document.owner(), document.repositoryName(), path, document.commitSha());
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        LOG.info("Document image served; userId={} documentId={} bytes={}", user.id(), id, bytes.length);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MarkdownRenderer.IMAGE_TYPES.get(extension)))
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }
}
