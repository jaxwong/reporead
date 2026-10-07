package com.reporead.document;

import com.reporead.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Owns repository_images: the image files of each connection's latest complete snapshot, which Obsidian embeds name
 * without a folder. Resolution follows Obsidian: a name with a folder must match the end of a path; a bare name matches a
 * file name. Of several matches, one in the note's own folder wins; otherwise the embed is ambiguous, never guessed.
 */
@Component
public class Attachments {
    private final JdbcClient db;

    public Attachments(JdbcClient db) {
        this.db = db;
    }

    /** Replaces the connection's image list with a snapshot's; callers hold the sync lock in the publishing transaction. */
    public void replace(long connectionId, List<String> paths) {
        db.sql("delete from repository_images where repository_connection_id = :connectionId").param("connectionId", connectionId).update();
        db.sql("insert into repository_images (repository_connection_id, path) select :connectionId, unnest(:paths::text[])")
            .param("connectionId", connectionId).param("paths", paths.toArray(String[]::new)).update();
    }

    /** The repository path an embed of [name] in the note at [notePath] means; 404 IMAGE_NOT_FOUND or 409 IMAGE_AMBIGUOUS. */
    public String resolve(long connectionId, String notePath, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        var matches = db.sql("select path from repository_images where repository_connection_id = :connectionId")
            .param("connectionId", connectionId).query(String.class).list().stream()
            .filter(path -> {
                String lower = path.toLowerCase(Locale.ROOT);
                return lower.equals(wanted) || lower.endsWith("/" + wanted);
            }).toList();
        if (matches.size() == 1) return matches.getFirst();
        if (matches.isEmpty()) {
            throw new ApiFailure(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "No image named " + name + " in the repository's latest refresh.");
        }
        String folder = notePath.contains("/") ? notePath.substring(0, notePath.lastIndexOf('/') + 1) : "";
        var local = matches.stream().filter(path -> path.startsWith(folder) && path.indexOf('/', folder.length()) < 0).toList();
        if (local.size() == 1) return local.getFirst();
        throw new ApiFailure(HttpStatus.CONFLICT, "IMAGE_AMBIGUOUS", matches.size() + " images are named " + name + "; give the embed its folder.");
    }
}
