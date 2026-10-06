package com.reporead.sync;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.document.Documents;
import com.reporead.github.GitHubApi;
import com.reporead.repository.RepositoryConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Reconciles a connection's Markdown documents with its default branch.
 * Ceiling: 2 GitHub requests (branch, recursive tree). All validation happens before the single publishing
 * transaction; any failure leaves the previous snapshot and checkpoint untouched.
 */
@Service
public class RepositorySync {
    private static final Logger LOG = LoggerFactory.getLogger(RepositorySync.class);
    static final int MAX_DOCUMENTS = 5_000;
    private final GitHubApi github;
    private final RepositoryConnections connections;
    private final Documents documents;
    private final TransactionTemplate transaction;

    public RepositorySync(GitHubApi github, RepositoryConnections connections, Documents documents, TransactionTemplate transaction) {
        this.github = github;
        this.connections = connections;
        this.documents = documents;
        this.transaction = transaction;
    }

    public record Result(long repositoryId, String commitSha, int documentCount, Instant syncedAt) {}

    public Result sync(AppUser user, long connectionId) {
        var connection = connections.find(user.id(), connectionId).orElseThrow(() ->
            new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Repository connection not found."));
        String token = github.userToken(user.githubUserId());
        var branch = github.branch(token, connection.owner(), connection.name(), connection.defaultBranch());
        var markdown = markdownDocuments(github.completeTree(token, connection.owner(), connection.name(), branch.treeSha()));
        Instant syncedAt = Instant.now();
        var moves = transaction.execute(status -> {
            connections.lockForSync(user.id(), connectionId);
            var applied = documents.publishSnapshot(connectionId, branch.commitSha(), markdown, syncedAt);
            connections.markSynced(connectionId, branch.commitSha(), syncedAt);
            return applied;
        });
        for (var move : moves) {
            LOG.info("Document moved; connectionId={} documentId={} from={} to={}", connectionId, move.documentId(), move.fromPath(), move.toPath());
        }
        LOG.info("Repository synced; userId={} connectionId={} commitSha={} documents={} moves={}",
            user.id(), connectionId, branch.commitSha(), markdown.size(), moves.size());
        return new Result(connectionId, branch.commitSha(), markdown.size(), syncedAt);
    }

    /** Regular-file blobs ending in .md (any case). Symlinks, submodules, and other files are not documents. */
    static List<Documents.SourceFile> markdownDocuments(List<GitHubApi.TreeEntry> tree) {
        var result = new ArrayList<Documents.SourceFile>();
        var paths = new HashSet<String>();
        for (var entry : tree) {
            boolean regularFile = entry.type().equals("blob") && (entry.mode().equals("100644") || entry.mode().equals("100755"));
            if (!regularFile || !entry.path().toLowerCase(Locale.ROOT).endsWith(".md")) continue;
            if (!paths.add(entry.path())) {
                throw new ApiFailure(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub tree listed one path twice.");
            }
            if (result.size() == MAX_DOCUMENTS) {
                throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, "DOCUMENT_LIMIT",
                    "This repository has more than 5000 Markdown files; no documents were changed.");
            }
            result.add(new Documents.SourceFile(entry.path(), entry.sha()));
        }
        return List.copyOf(result);
    }
}
