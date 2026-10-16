package com.reporead.datacontrol;

import com.reporead.annotation.Annotations;
import com.reporead.auth.AppSessions;
import com.reporead.auth.AppUser;
import com.reporead.github.GitHubApi;
import com.reporead.repository.RepositoryConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
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
    private final ConnectionData data;
    private final Annotations annotations;
    private final AppSessions sessions;
    private final OAuth2AuthorizedClientService clients;
    private final TransactionTemplate transaction;
    private final GitHubApi github;

    public AccountDeletion(
            RepositoryConnections connections,
            ConnectionData data,
            Annotations annotations,
            AppSessions sessions,
            OAuth2AuthorizedClientService clients,
            TransactionTemplate transaction,
            GitHubApi github) {
        this.connections = connections;
        this.data = data;
        this.annotations = annotations;
        this.sessions = sessions;
        this.clients = clients;
        this.transaction = transaction;
        this.github = github;
    }

    record Deleted(int repositories, int readingStates, int bookmarks, int highlights, int cards) {}

    /**
     * One transaction deletes the account. Revoking cannot be undone, so it waits for the commit: a deletion that fails
     * leaves the account and its GitHub authorization intact. RepoRead keeps no credential afterwards. The App stays
     * installed on GitHub until the user uninstalls it.
     */
    Deleted delete(AppUser user) {
        var token = github.tokenToRevoke(user.githubUserId());
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
            clients.removeAuthorizedClient("github", Long.toString(user.githubUserId()));
            return new Deleted(repositories, reading, bookmarks, highlights, cards);
        });
        token.ifPresent(value -> github.revokeAuthorization(user.githubUserId(), value));
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
