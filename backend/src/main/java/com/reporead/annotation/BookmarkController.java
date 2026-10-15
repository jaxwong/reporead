package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.document.Documents;
import com.reporead.repository.RepositoryConnections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final Documents documents;
    private final RepositoryConnections connections;
    private final TransactionTemplate transaction;

    public BookmarkController(
            Bookmarks bookmarks,
            Documents documents,
            RepositoryConnections connections,
            TransactionTemplate transaction) {
        this.bookmarks = bookmarks;
        this.documents = documents;
        this.connections = connections;
        this.transaction = transaction;
    }

    record BookmarkList(List<Bookmarks.Bookmark> bookmarks) {}

    @GetMapping("/api/bookmarks")
    BookmarkList list(@AuthenticationPrincipal AppUser user) {
        return new BookmarkList(bookmarks.list(user.id()));
    }

    /** A bookmark is of the note, whatever its version, so the request has no body. */
    @PutMapping("/api/documents/{id}/bookmark")
    Bookmarks.Bookmark set(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return transaction.execute(status -> {
            connections.lockForDocumentWrite(user.id(), id);
            return bookmarks.set(user.id(), id);
        });
    }

    @DeleteMapping("/api/documents/{id}/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        // Clearing is a delete scoped to the user and takes no lock, so the note's ownership is checked here for the
        // 404.
        documents.find(user.id(), id).orElseThrow(ApiFailure::notFound);
        bookmarks.clear(user.id(), id);
    }
}
