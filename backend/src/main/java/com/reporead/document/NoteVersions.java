package com.reporead.document;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.github.GitHubApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/**
 * Renders one exact version of an owned note from GitHub: the single source of canonical block text for both the
 * reader and anchor validation. One GitHub request (the blob by SHA); the bytes must hash to that SHA.
 */
@Component
public class NoteVersions {
    private final GitHubApi github;

    public NoteVersions(GitHubApi github) {
        this.github = github;
    }

    public record Rendered(MarkdownRenderer.RenderedNote note, int bytes, String markdown) {}

    public Rendered render(AppUser user, Documents.Located document, String blobSha) {
        return render(user, document.owner(), document.repositoryName(), document.path(), blobSha);
    }

    /** The same for a repository path that may not have a document yet, such as a path in a snapshot being reconciled. */
    public Rendered render(AppUser user, String owner, String repositoryName, String path, String blobSha) {
        String token = github.userToken(user.githubUserId());
        byte[] bytes = github.blob(token, owner, repositoryName, blobSha);
        if (!MarkdownRenderer.blobSha(bytes).equals(blobSha)) {
            throw new ApiFailure(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned bytes that do not match the note's blob SHA.");
        }
        String markdown;
        try {
            markdown = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_CONTENT", "This note is not valid UTF-8 text.");
        }
        try {
            return new Rendered(MarkdownRenderer.render(markdown, blobSha, path), bytes.length, markdown);
        } catch (MarkdownRenderer.ContentRejected rejected) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_CONTENT", rejected.getMessage());
        }
    }
}
