package com.reporead.document;

import com.reporead.auth.AppUser;
import com.reporead.auth.GitHubTokenLifecycle;
import com.reporead.github.GitHubApi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

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
    private final GitHubTokenLifecycle credentials;

    public Attachments(JdbcClient db, GitHubApi github, GitHubTokenLifecycle credentials) {
        this.db = db;
        this.github = github;
        this.credentials = credentials;
    }

    /**
     * The image at the normalized repository {@code path}, at the saved page's explicit {@code commitSha}: one GitHub
     * request with the user's token, which reads the whole repository anyway. Not cached on the server.
     */
    public byte[] image(AppUser user, Documents.Located document, String path, String commitSha) {
        String token = credentials.githubUserToken(user.githubUserId());
        return github.imageFile(token, document.owner(), document.repositoryName(), path, commitSha);
    }

    /** Read together with the document/checkpoint in the content owner's short database snapshot. */
    public List<String> paths(long connectionId) {
        return db.sql("SELECT path FROM repository_images WHERE repository_connection_id = :connectionId")
                .param("connectionId", connectionId)
                .query(String.class)
                .list();
    }

    /**
     * Resolves embeds once, before the page leaves its captured snapshot. Missing/ambiguous images remain visibly
     * unavailable without changing canonical text; they never acquire a different meaning when images are fetched.
     */
    public static void bind(Document page, String notePath, List<String> imagePaths) {
        for (var image : page.select("img[data-embed]")) {
            String name = image.attr("data-embed");
            var matches = matches(imagePaths, notePath, name);
            if (matches.size() == 1) {
                image.attr(
                        "src",
                        MarkdownRenderer.IMAGE_PREFIX
                                + UriUtils.encodePath(matches.getFirst(), StandardCharsets.UTF_8));
                image.removeAttr("data-embed");
            } else {
                String reason = matches.isEmpty()
                        ? "No image named " + name + " in the saved page's repository snapshot."
                        : matches.size() + " images are named " + name + "; give the embed its folder.";
                image.replaceWith(new Element("span")
                        .addClass("image-blocked")
                        .attr("data-label", "[Image unavailable: " + reason + "]"));
            }
        }
    }

    /**
     * Replaces the connection's image list with a snapshot's; callers hold the sync lock in the publishing transaction.
     */
    public void replaceImages(long connectionId, List<String> paths) {
        db.sql("DELETE FROM repository_images WHERE repository_connection_id = :connectionId")
                .param("connectionId", connectionId)
                .update();
        db.sql(
                        "INSERT INTO repository_images (repository_connection_id, path) SELECT :connectionId, unnest(:paths::text[])")
                .param("connectionId", connectionId)
                .param("paths", paths.toArray(String[]::new))
                .update();
    }

    /** Candidate paths, narrowed to a unique same-folder match when possible; zero/many are normal source outcomes. */
    private static List<String> matches(List<String> paths, String notePath, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        var matches = paths.stream()
                .filter(path -> {
                    String lower = path.toLowerCase(Locale.ROOT);
                    return lower.equals(wanted) || lower.endsWith("/" + wanted);
                })
                .toList();
        if (matches.size() < 2) return matches;
        String folder = notePath.contains("/") ? notePath.substring(0, notePath.lastIndexOf('/') + 1) : "";
        var local = matches.stream()
                .filter(path -> path.startsWith(folder) && path.indexOf('/', folder.length()) < 0)
                .toList();
        return local.size() == 1 ? local : matches;
    }
}
