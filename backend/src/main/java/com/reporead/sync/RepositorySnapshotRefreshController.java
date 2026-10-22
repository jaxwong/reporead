package com.reporead.sync;

import com.reporead.auth.AppUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** External-call ceiling: 2 GitHub requests plus at most 128 (see RepositorySnapshotRefresh). */
@Tag(name = "sync-controller")
@RestController
public class RepositorySnapshotRefreshController {
    private final RepositorySnapshotRefresh snapshotRefresh;

    public RepositorySnapshotRefreshController(RepositorySnapshotRefresh snapshotRefresh) {
        this.snapshotRefresh = snapshotRefresh;
    }

    // Stable route used by installed Android versions; it refreshes the GitHub-backed repository snapshot.
    @Operation(operationId = "sync")
    @PostMapping("/api/repositories/{id}/sync")
    RepositorySyncResult refresh(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return snapshotRefresh.refresh(user, id);
    }
}
