package com.reporead.datacontrol;

import com.reporead.ApiFailure;
import com.reporead.annotation.Annotations;
import com.reporead.auth.AppSessions;
import com.reporead.auth.AppUser;
import com.reporead.github.GitHubApi;
import com.reporead.repository.RepositoryConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deleting what RepoRead stores, through each table's owner (ADR-11): disconnecting a repository, and deleting the
 * account. External-call ceilings: stored data and disconnect 0; account deletion 2 GitHub requests (renewing an
 * expiring token, then revoking the authorization).
 */
@RestController
public class DataControlController {
    private static final Logger LOG = LoggerFactory.getLogger(DataControlController.class);
    private final RepositoryConnections connections;
    private final ConnectionData data;
    private final Annotations annotations;
    private final AppSessions sessions;
    private final OAuth2AuthorizedClientService clients;
    private final TransactionTemplate transaction;
    private final GitHubApi github;

    public DataControlController(
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

    /** What disconnecting this repository would delete, for the confirmation. */
    @GetMapping("/api/repositories/{id}/stored-data")
    ConnectionData.Counts storedData(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        connections.find(user.id(), id).orElseThrow(ApiFailure::notFound);
        return data.count(id);
    }

    /**
     * Disconnects a repository: deletes the connection and everything stored for it in one transaction, serialized with
     * its syncs. GitHub is not modified, and the GitHub App stays installed until the user removes it on GitHub.
     */
    @DeleteMapping("/api/repositories/{id}")
    ConnectionData.Disconnected disconnect(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        var deleted = transaction.execute(status -> {
            connections.lockForSync(user.id(), id);
            return data.delete(id);
        });
        LOG.info(
                "Repository disconnected; userId={} connectionId={} documents={} readingStates={} bookmarks={} highlights={} cards={}",
                user.id(),
                id,
                deleted.documentIds().size(),
                deleted.readingStates(),
                deleted.bookmarks(),
                deleted.highlights(),
                deleted.cards());
        return deleted;
    }

    record AccountDeleted(int repositories, int readingStates, int bookmarks, int highlights, int cards) {}

    /**
     * Deletes everything RepoRead holds for the signed-in user in one transaction — every connection and its data, the
     * annotation mutation records, sign-in codes, sessions, the user, and the stored GitHub token — then revokes
     * RepoRead's authorization on GitHub with that token, read before the deletion removed it. Revoking cannot be
     * undone, so it waits for the commit: a deletion that fails leaves the account and its GitHub authorization intact.
     * RepoRead keeps no credential afterwards. The App stays installed on GitHub until the user uninstalls it.
     */
    @DeleteMapping("/api/account")
    AccountDeleted delete(@AuthenticationPrincipal AppUser user) {
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
            return new AccountDeleted(repositories, reading, bookmarks, highlights, cards);
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
