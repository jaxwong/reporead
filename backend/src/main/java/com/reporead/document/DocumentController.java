package com.reporead.document;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.github.GitHubApi;
import com.reporead.repository.RepositoryConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** External-call ceilings: listing 0 GitHub requests; content 1 (the note's blob at its current SHA). */
@RestController
public class DocumentController {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentController.class);
    private final RepositoryConnections connections;
    private final Documents documents;
    private final GitHubApi github;

    public DocumentController(RepositoryConnections connections, Documents documents, GitHubApi github) {
        this.connections = connections;
        this.documents = documents;
        this.github = github;
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
        String token = github.userToken(user.githubUserId());
        byte[] bytes = github.blob(token, document.owner(), document.repositoryName(), document.blobSha());
        if (!MarkdownRenderer.blobSha(bytes).equals(document.blobSha())) {
            throw new ApiFailure(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned bytes that do not match the note's blob SHA.");
        }
        String markdown;
        try {
            markdown = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_CONTENT", "This note is not valid UTF-8 text.");
        }
        MarkdownRenderer.RenderedNote rendered;
        try {
            rendered = MarkdownRenderer.render(markdown, document.blobSha(), document.path());
        } catch (MarkdownRenderer.ContentRejected rejected) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_CONTENT", rejected.getMessage());
        }
        LOG.info("Document rendered; userId={} documentId={} blobSha={} bytes={} blocks={} diagrams={}",
            user.id(), id, document.blobSha(), bytes.length, rendered.blocks().size(), rendered.diagramCount());
        return new Content(id, document.path(), document.title(), document.blobSha(), document.commitSha(),
            rendered.blocks().size(), rendered.diagramCount(), rendered.html());
    }

    private static ApiFailure notFound() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }
}
