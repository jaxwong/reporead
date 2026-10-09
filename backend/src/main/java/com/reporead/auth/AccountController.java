package com.reporead.auth;

import com.reporead.annotation.Annotations;
import com.reporead.repository.ConnectionData;
import com.reporead.repository.RepositoryConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account deletion. External-call ceiling: 0 GitHub requests. */
@RestController
public class AccountController {
    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);
    private final RepositoryConnections connections;
    private final ConnectionData data;
    private final Annotations annotations;
    private final AppSessions sessions;
    private final OAuth2AuthorizedClientService clients;
    private final TransactionTemplate transaction;

    public AccountController(RepositoryConnections connections, ConnectionData data, Annotations annotations, AppSessions sessions,
                             OAuth2AuthorizedClientService clients, TransactionTemplate transaction) {
        this.connections = connections;
        this.data = data;
        this.annotations = annotations;
        this.sessions = sessions;
        this.clients = clients;
        this.transaction = transaction;
    }

    record Deleted(int repositories, int readingStates, int bookmarks, int highlights, int cards) {}

    /**
     * Deletes everything RepoRead holds for the signed-in user in one transaction — every connection and its data, the
     * annotation mutation records, sign-in codes, sessions, and the user — then drops the server's in-memory GitHub token.
     * RepoRead keeps no credential afterwards. GitHub is not called: the user revokes the authorization and uninstalls
     * the App on GitHub if they want.
     */
    @DeleteMapping("/api/account")
    Deleted delete(@AuthenticationPrincipal AppUser user) {
        var deleted = transaction.execute(status -> {
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
            return new Deleted(repositories, reading, bookmarks, highlights, cards);
        });
        clients.removeAuthorizedClient("github", Long.toString(user.githubUserId()));
        LOG.info("Account deleted; userId={} repositories={} readingStates={} bookmarks={} highlights={} cards={}",
            user.id(), deleted.repositories(), deleted.readingStates(), deleted.bookmarks(), deleted.highlights(), deleted.cards());
        return deleted;
    }
}
