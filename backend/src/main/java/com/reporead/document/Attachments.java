package com.reporead.document;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.auth.AppUser;
import com.reporead.auth.GitHubCredentials;
import com.reporead.github.GitHubApi;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Owns a repository's images: repository_images, the image files of each connection's latest complete snapshot, which
 * Obsidian embeds name without a folder, and fetching one from GitHub. Resolution follows Obsidian: a name with a
 * folder must match the end of a path; a bare name matches a file name. Of several matches, one in the note's own
 * folder wins; otherwise the embed is ambiguous, never guessed.
 */
@Component
public class Attachments {
    private final JdbcClient db;
    private final GitHubApi github;
    private final GitHubCredentials credentials;

    public Attachments(JdbcClient db, GitHubApi github, GitHubCredentials credentials) {
        this.db = db;
        this.github = github;
        this.credentials = credentials;
    }

    /**
     * The image at the normalized repository {@code path}, at the commit of the owned, listed {@code document}: one
     * GitHub request with the user's token, which reads the whole repository anyway. Not cached on the server.
     */
    public byte[] image(AppUser user, Documents.Located document, String path) {
        String token = credentials.githubUserToken(user.githubUserId());
        return github.imageFile(token, document.owner(), document.repositoryName(), path, document.commitSha());
    }

    /**
     * Replaces the connection's image list with a snapshot's; callers hold the sync lock in the publishing transaction.
     */
    public void replace(long connectionId, List<String> paths) {
        db.sql("delete from repository_images where repository_connection_id = :connectionId")
                .param("connectionId", connectionId)
                .update();
        db.sql(
                        "insert into repository_images (repository_connection_id, path) select :connectionId, unnest(:paths::text[])")
                .param("connectionId", connectionId)
                .param("paths", paths.toArray(String[]::new))
                .update();
    }

    /**
     * The repository path an embed of {@code name} in the note at {@code notePath} means; 404 IMAGE_NOT_FOUND or 409
     * IMAGE_AMBIGUOUS.
     */
    public String resolve(long connectionId, String notePath, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        var matches = db
                .sql("select path from repository_images where repository_connection_id = :connectionId")
                .param("connectionId", connectionId)
                .query(String.class)
                .list()
                .stream()
                .filter(path -> {
                    String lower = path.toLowerCase(Locale.ROOT);
                    return lower.equals(wanted) || lower.endsWith("/" + wanted);
                })
                .toList();
        if (matches.size() == 1) return matches.getFirst();
        if (matches.isEmpty()) {
            throw new ApiFailure(
                    Code.IMAGE_NOT_FOUND, "No image named " + name + " in the repository's latest refresh.");
        }
        String folder = notePath.contains("/") ? notePath.substring(0, notePath.lastIndexOf('/') + 1) : "";
        var local = matches.stream()
                .filter(path -> path.startsWith(folder) && path.indexOf('/', folder.length()) < 0)
                .toList();
        if (local.size() == 1) return local.getFirst();
        throw new ApiFailure(
                Code.IMAGE_AMBIGUOUS, matches.size() + " images are named " + name + "; give the embed its folder.");
    }
}
