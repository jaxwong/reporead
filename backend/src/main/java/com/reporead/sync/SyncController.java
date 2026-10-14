package com.reporead.sync;

import com.reporead.auth.AppUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** External-call ceiling: 2 GitHub requests plus at most 8 (see RepositorySync). */
@RestController
public class SyncController {
    private final RepositorySync sync;

    public SyncController(RepositorySync sync) {
        this.sync = sync;
    }

    @PostMapping("/api/repositories/{id}/sync")
    RepositorySync.Result sync(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return sync.sync(user, id);
    }
}
