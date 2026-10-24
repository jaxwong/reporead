package com.reporead.document;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.Hashes;
import com.reporead.auth.AppUser;
import com.reporead.auth.GitHubTokenLifecycle;
import com.reporead.github.GitHubApi;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Renders one exact version of an owned note from GitHub: the single source of canonical block text for both the reader
 * and anchor validation. One GitHub request (the blob by SHA); the bytes must hash to that SHA.
 */
@Component
public class NoteVersions {
    private final GitHubApi github;
    private final GitHubTokenLifecycle credentials;

    public NoteVersions(GitHubApi github, GitHubTokenLifecycle credentials) {
        this.github = github;
        this.credentials = credentials;
    }

    public record Rendered(MarkdownRenderer.RenderedNote note, int bytes) {}

    /**
     * A version GitHub no longer has (rewritten history), or one that is not a note RepoRead can read: what callers
     * comparing versions treat as unavailable rather than as a failure. Anything else fails the request.
     */
    public static boolean versionUnavailable(ApiFailure failure) {
        return failure.code == Code.SOURCE_NOT_FOUND || failure.code == Code.UNSUPPORTED_CONTENT;
    }

    public Rendered render(AppUser user, Documents.Located document, String blobSha) {
        return render(user, document.owner(), document.repositoryName(), document.path(), blobSha);
    }

    /**
     * The same for a repository path that may not have a document yet, such as a path in a snapshot being reconciled.
     */
    public Rendered render(AppUser user, String owner, String repositoryName, String path, String blobSha) {
        String token = credentials.githubUserToken(user.githubUserId());
        byte[] bytes = github.blob(token, owner, repositoryName, blobSha);
        if (!Hashes.gitBlobSha(bytes).equals(blobSha)) {
            throw new ApiFailure(
                    Code.GITHUB_INVALID_RESPONSE, "GitHub returned bytes that do not match the note's blob SHA.");
        }
        String markdown;
        try {
            markdown = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new ApiFailure(Code.UNSUPPORTED_CONTENT, "This note is not valid UTF-8 text.");
        }
        try {
            return new Rendered(MarkdownRenderer.render(markdown, blobSha, path), bytes.length);
        } catch (MarkdownRenderer.ContentRejected rejected) {
            throw new ApiFailure(Code.UNSUPPORTED_CONTENT, rejected.getMessage());
        }
    }
}
