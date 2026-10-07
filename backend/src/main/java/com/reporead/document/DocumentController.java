package com.reporead.document;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.github.GitHubApi;
import com.reporead.repository.RepositoryConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * External-call ceilings: listing 0 GitHub requests; content 1 (the note's blob); image 1 (the file at the note's commit);
 * changes 2 (the two versions' blobs), 0 when they are the same version.
 */
@RestController
public class DocumentController {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentController.class);
    private final RepositoryConnections connections;
    private final Documents documents;
    private final GitHubApi github;
    private final NoteVersions noteVersions;

    public DocumentController(RepositoryConnections connections, Documents documents, GitHubApi github, NoteVersions noteVersions) {
        this.connections = connections;
        this.documents = documents;
        this.github = github;
        this.noteVersions = noteVersions;
    }

    record DocumentList(long repositoryId, String lastSyncedCommitSha, List<Documents.Summary> documents) {}

    @GetMapping("/api/repositories/{id}/documents")
    DocumentList list(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        var connection = connections.find(user.id(), id).orElseThrow(DocumentController::notFound);
        return new DocumentList(id, connection.lastSyncedCommitSha(), documents.list(id));
    }

    /** [text]: the canonical text of the note's blocks, one per line, for search on the phone. */
    record Content(long documentId, String path, String title, String sourceBlobSha, String commitSha,
                   int blockCount, int diagramCount, String html, String text) {}

    @GetMapping("/api/documents/{id}/content")
    Content content(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        var document = documents.find(user.id(), id).orElseThrow(DocumentController::notFound);
        if (document.deleted()) {
            throw new ApiFailure(HttpStatus.GONE, "DOCUMENT_DELETED", "This note was not present in the latest complete repository refresh.");
        }
        var version = noteVersions.render(user, document, document.blobSha());
        var rendered = version.note();
        LOG.info("Document rendered; userId={} documentId={} blobSha={} bytes={} blocks={} diagrams={}",
            user.id(), id, document.blobSha(), version.bytes(), rendered.blocks().size(), rendered.diagramCount());
        return new Content(id, document.path(), document.title(), document.blobSha(), document.commitSha(),
            rendered.blocks().size(), rendered.diagramCount(), rendered.html(),
            String.join("\n", rendered.blocks().stream().map(MarkdownRenderer.Block::text).toList()));
    }

    enum ChangeStatus { CHANGED, UNCHANGED, SINCE_UNAVAILABLE, TOO_LARGE }

    /** [reason] says why the older version is unavailable; [sections] is empty unless the status is CHANGED. */
    record Changes(long documentId, String sinceBlobSha, String toBlobSha, ChangeStatus status, String reason,
                   int addedLines, int removedLines, List<NoteChanges.Section> sections) {}

    /**
     * Which sections changed between [since], the version the user last read, and [to], the version on their screen,
     * whose heading blocks the sections name. The phone supplies both: its last-read version can be newer than the
     * server's until its pending reading saves are sent. A version GitHub no longer has, or can no longer be read as a
     * note, makes the comparison unavailable; any other failure fails the request.
     */
    @GetMapping("/api/documents/{id}/changes")
    Changes changes(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestParam(required = false) String since,
                    @RequestParam(required = false) String to) {
        if (since == null || to == null || !since.matches("[0-9a-f]{40}") || !to.matches("[0-9a-f]{40}")) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_VERSIONS", "since and to must be lowercase 40-character blob SHAs.");
        }
        var document = documents.find(user.id(), id).orElseThrow(DocumentController::notFound);
        if (document.deleted()) {
            throw new ApiFailure(HttpStatus.GONE, "DOCUMENT_DELETED", "This note was not present in the latest complete repository refresh.");
        }
        Changes result;
        if (since.equals(to)) {
            result = new Changes(id, since, to, ChangeStatus.UNCHANGED, null, 0, 0, List.of());
        } else {
            result = compare(user, document, since, to);
        }
        LOG.info("Changes compared; userId={} documentId={} since={} to={} status={} sections={} added={} removed={}",
            user.id(), id, since, to, result.status(), result.sections().size(), result.addedLines(), result.removedLines());
        return result;
    }

    private Changes compare(AppUser user, Documents.Located document, String since, String to) {
        NoteVersions.Rendered old;
        try {
            old = noteVersions.render(user, document, since);
        } catch (ApiFailure failure) {
            if (!failure.code.equals("SOURCE_NOT_FOUND") && !failure.code.equals("UNSUPPORTED_CONTENT")) throw failure;
            return new Changes(document.id(), since, to, ChangeStatus.SINCE_UNAVAILABLE, failure.getMessage(), 0, 0, List.of());
        }
        var current = noteVersions.render(user, document, to);
        return NoteChanges.compare(MarkdownRenderer.sourceLines(old.markdown()), old.note().headings(),
                MarkdownRenderer.sourceLines(current.markdown()), current.note().headings())
            .map(comparison -> new Changes(document.id(), since, to, ChangeStatus.CHANGED, null,
                comparison.addedLines(), comparison.removedLines(), comparison.sections()))
            .orElseGet(() -> new Changes(document.id(), since, to, ChangeStatus.TOO_LARGE, null, 0, 0, List.of()));
    }

    /** A repository image referenced by an owned note, fetched at the note's current commit. Not cached on the server. */
    @GetMapping("/api/documents/{id}/image")
    ResponseEntity<byte[]> image(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestParam String path) {
        if (!MarkdownRenderer.isImagePath(path)) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_PATH", "path must be a normalized repository path to a png, jpg, gif, webp, or svg image.");
        }
        var document = documents.find(user.id(), id).orElseThrow(DocumentController::notFound);
        if (document.deleted()) {
            throw new ApiFailure(HttpStatus.GONE, "DOCUMENT_DELETED", "This note was not present in the latest complete repository refresh.");
        }
        String token = github.userToken(user.githubUserId());
        byte[] bytes = github.imageFile(token, document.owner(), document.repositoryName(), path, document.commitSha());
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        LOG.info("Document image served; userId={} documentId={} bytes={}", user.id(), id, bytes.length);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(MarkdownRenderer.IMAGE_TYPES.get(extension)))
            .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    private static ApiFailure notFound() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }
}
