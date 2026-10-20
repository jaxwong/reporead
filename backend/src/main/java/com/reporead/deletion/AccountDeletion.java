// Owns coordinated account-data deletion; credential lifecycle admission comes before its database transaction.

package com.reporead.deletion;

import com.reporead.annotation.Annotations;
import com.reporead.auth.AppSessions;
import com.reporead.auth.AppUser;
import com.reporead.auth.GitHubCredentials;
import com.reporead.repository.RepositoryConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes everything RepoRead holds for a user (ADR-11) — every connection and its data, the annotation mutation
 * records, sign-in codes, sessions, the user, and the stored GitHub token — then revokes RepoRead's authorization on
 * GitHub with that token, read before the deletion removed it. External calls: at most 2 GitHub requests (renewing an
 * expiring token, then revoking).
 */
@Service
public class AccountDeletion {
    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletion.class);
    private final RepositoryConnections connections;
    private final ConnectionDeletion data;
    private final Annotations annotations;
    private final AppSessions sessions;
    private final GitHubCredentials credentials;
    private final TransactionTemplate transaction;

    public AccountDeletion(
            RepositoryConnections connections,
            ConnectionDeletion data,
            Annotations annotations,
            AppSessions sessions,
            GitHubCredentials credentials,
            TransactionTemplate transaction) {
        this.connections = connections;
        this.data = data;
        this.annotations = annotations;
        this.sessions = sessions;
        this.credentials = credentials;
        this.transaction = transaction;
    }

    /** Owns the committed deletion counts that authorize the phone to remove its own copy. */
    record Deleted(int repositories, int readingStates, int bookmarks, int highlights, int cards) {}

    /**
     * One transaction deletes the account. Revoking cannot be undone, so it waits for the commit: a deletion that fails
     * leaves the account and its GitHub authorization intact. RepoRead keeps no credential afterwards. The App stays
     * installed on GitHub until the user uninstalls it.
     */
    Deleted delete(AppUser user, String sessionToken) {
        return credentials.deletingAccount(sessionToken, user, () -> deleteAdmitted(user));
    }

    /** Runs inside lifecycle admission; returns after commit so the credential owner can safely revoke the grant. */
    private Deleted deleteAdmitted(AppUser user) {
        var deleted = transaction.execute(status -> {
            // First, so a connection being added concurrently commits before the list below, or waits and then fails.
            sessions.lockUserForDeletion(user.id());
            int repositories = 0;
            int reading = 0;
            int bookmarks = 0;
            int highlights = 0;
            int cards = 0;
            for (var connection : connections.list(user.id())) {
                connections.lockForSync(user.id(), connection.id());
                var removed = data.delete(connection.id());
                repositories++;
                reading += removed.readingStates();
                bookmarks += removed.bookmarks();
                highlights += removed.highlights();
                cards += removed.cards();
            }
            annotations.deleteMutations(user.id());
            sessions.deleteUser(user.id());
            credentials.forgetForAccountDeletion(user.githubUserId());
            return new Deleted(repositories, reading, bookmarks, highlights, cards);
        });
        LOG.info(
                "Account deleted; userId={} repositories={} readingStates={} bookmarks={} highlights={} cards={}",
                user.id(),
                deleted.repositories(),
                deleted.readingStates(),
                deleted.bookmarks(),
                deleted.highlights(),
                deleted.cards());
        return deleted;
    }
}
