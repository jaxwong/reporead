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

/** External-call ceilings: listing 0 GitHub requests; content 1 (the note's blob); image 1 (the file at the note's commit). */
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

    record Content(long documentId, String path, String title, String sourceBlobSha, String commitSha,
                   int blockCount, int diagramCount, String html) {}

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
            rendered.blocks().size(), rendered.diagramCount(), rendered.html());
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
