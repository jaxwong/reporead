package com.reporead.annotation;

import com.reporead.auth.AppUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** No GitHub calls. PUT and DELETE are idempotent, so the app can replay an offline toggle safely. */
@RestController
public class BookmarkController {
    private final Bookmarks bookmarks;

    public BookmarkController(Bookmarks bookmarks) {
        this.bookmarks = bookmarks;
    }

    record BookmarkList(List<Bookmarks.Bookmark> bookmarks) {}

    /** Kept only for app releases from before GET /api/library; the library contract step deletes it (ADR-08). */
    @GetMapping("/api/bookmarks")
    BookmarkList list(@AuthenticationPrincipal AppUser user) {
        return new BookmarkList(bookmarks.list(user.id()));
    }

    /** A bookmark is of the note, whatever its version, so the request has no body. */
    @PutMapping("/api/documents/{id}/bookmark")
    Bookmarks.Bookmark set(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return bookmarks.set(user.id(), id);
    }

    @DeleteMapping("/api/documents/{id}/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        bookmarks.clear(user.id(), id);
    }
}
