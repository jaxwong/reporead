package com.reporead.library;

import com.reporead.auth.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** External-call ceiling: 0 GitHub requests. The phone's one read of the server's lists (ADR-08). */
@RestController
public class LibraryController {
    private static final Logger LOG = LoggerFactory.getLogger(LibraryController.class);
    private final Library library;

    public LibraryController(Library library) {
        this.library = library;
    }

    @GetMapping("/api/library")
    Library.Snapshot library(@AuthenticationPrincipal AppUser user) {
        var snapshot = library.read(user.id());
        LOG.info(
                "Library read; userId={} repositories={} readingStates={} bookmarks={} annotations={} reviews={}",
                user.id(),
                snapshot.repositories().size(),
                snapshot.readingStates().size(),
                snapshot.bookmarks().size(),
                snapshot.notebook().annotations().size(),
                snapshot.notebook().reviews().size());
        return snapshot;
    }
}
