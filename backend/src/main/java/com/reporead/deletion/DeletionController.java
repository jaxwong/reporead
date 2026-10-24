// Owns deletion HTTP routes; ConnectionDeletion and AccountDeletion own the actual deletion decisions.

package com.reporead.deletion;

import com.reporead.auth.AppUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deleting what RepoRead stores (ADR-11): disconnecting a repository, and deleting the account. External-call ceilings:
 * stored data and disconnect 0; account deletion 2 GitHub requests (renewing an expiring token, then revoking the
 * authorization). GitHub is not modified, and the GitHub App stays installed until the user removes it on GitHub.
 */
@RestController
public class DeletionController {
    private final ConnectionDeletion data;
    private final AccountDeletion accounts;

    public DeletionController(ConnectionDeletion data, AccountDeletion accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    /** What disconnecting this repository would delete, for the confirmation. */
    @GetMapping("/api/repositories/{id}/stored-data")
    ConnectionDeletion.Counts storedData(@AuthenticationPrincipal AppUser user, @PathVariable("id") long connectionId) {
        return data.count(user.id(), connectionId);
    }

    @DeleteMapping("/api/repositories/{id}")
    ConnectionDeletion.Disconnected disconnect(
            @AuthenticationPrincipal AppUser user, @PathVariable("id") long connectionId) {
        return data.disconnect(user.id(), connectionId);
    }

    @DeleteMapping("/api/account")
    AccountDeletion.Deleted delete(@AuthenticationPrincipal AppUser user, Authentication authentication) {
        return accounts.delete(user, (String) authentication.getCredentials());
    }
}
