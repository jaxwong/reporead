package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.document.Documents;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** No GitHub calls. PUT and DELETE are idempotent, so the app can replay an offline toggle safely. */
@RestController
public class BookmarkController {
    private final Bookmarks bookmarks;
    private final Documents documents;

    public BookmarkController(Bookmarks bookmarks, Documents documents) {
        this.bookmarks = bookmarks;
        this.documents = documents;
    }

    record BookmarkList(List<Bookmarks.Bookmark> bookmarks) {}

    @GetMapping("/api/bookmarks")
    BookmarkList list(@AuthenticationPrincipal AppUser user) {
        return new BookmarkList(bookmarks.list(user.id()));
    }

    record SetRequest(String sourceBlobSha) {}

    @PutMapping("/api/documents/{id}/bookmark")
    Bookmarks.Bookmark set(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody SetRequest body) {
        if (body.sourceBlobSha() == null || !body.sourceBlobSha().matches("[0-9a-f]{40}")) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_BOOKMARK", "sourceBlobSha must be the Git blob SHA of the version bookmarked.");
        }
        requireOwned(user, id);
        return bookmarks.set(user.id(), id, body.sourceBlobSha());
    }

    @DeleteMapping("/api/documents/{id}/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        requireOwned(user, id);
        bookmarks.clear(user.id(), id);
    }

    private void requireOwned(AppUser user, long documentId) {
        documents.find(user.id(), documentId).orElseThrow(() -> new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found."));
    }
}
