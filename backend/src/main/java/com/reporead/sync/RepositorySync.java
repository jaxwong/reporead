package com.reporead.sync;

import com.reporead.ApiFailure;
import com.reporead.auth.AppUser;
import com.reporead.document.Documents;
import com.reporead.document.NoteVersions;
import com.reporead.github.GitHubApi;
import com.reporead.repository.RepositoryConnections;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reconciles a connection's Markdown documents with its default branch.
 * Ceiling: 2 GitHub requests (branch, recursive tree) plus at most 8 blob reads to recognize notes moved and edited.
 * All validation happens before the single publishing transaction; any failure leaves the previous snapshot and
 * checkpoint untouched.
 */
@Service
public class RepositorySync {
    private static final Logger LOG = LoggerFactory.getLogger(RepositorySync.class);
    static final int MAX_DOCUMENTS = 5_000;
    static final String SYNCS = "reporead.github.syncs";
    static final String DOCUMENTS_SYNCED = "reporead.documents.synced";
    /** Vanished documents plus new paths compared by content in one sync; more is skipped (each blob is one request). */
    static final int MAX_CONTENT_MOVE_CANDIDATES = 8;
    private final GitHubApi github;
    private final RepositoryConnections connections;
    private final Documents documents;
    private final TransactionTemplate transaction;
    private final NoteVersions noteVersions;
    private final MeterRegistry meters;

    public RepositorySync(GitHubApi github, RepositoryConnections connections, Documents documents, TransactionTemplate transaction,
                          NoteVersions noteVersions, MeterRegistry meters) {
        this.meters = meters;
        this.github = github;
        this.connections = connections;
        this.documents = documents;
        this.transaction = transaction;
        this.noteVersions = noteVersions;
    }

    public record Result(long repositoryId, String commitSha, int documentCount, Instant syncedAt) {}

    /**
     * Counts every sync in reporead.github.syncs by outcome and failure code, and the documents of each successful one in
     * reporead.documents.synced.
     */
    public Result sync(AppUser user, long connectionId) {
        Result result;
        try {
            result = publish(user, connectionId);
        } catch (RuntimeException failure) {
            meters.counter(SYNCS, "outcome", "failure", "code", failure instanceof ApiFailure api ? api.code : "UNEXPECTED").increment();
            throw failure;
        }
        meters.counter(SYNCS, "outcome", "success", "code", "NONE").increment();
        meters.counter(DOCUMENTS_SYNCED).increment(result.documentCount());
        return result;
    }

    private Result publish(AppUser user, long connectionId) {
        var connection = connections.find(user.id(), connectionId).orElseThrow(() ->
            new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Repository connection not found."));
        String token = github.userToken(user.githubUserId());
        var branch = github.branch(token, connection.owner(), connection.name(), connection.defaultBranch());
        var markdown = markdownDocuments(github.completeTree(token, connection.owner(), connection.name(), branch.treeSha()));
        var contentMoves = contentMoves(user, connection, markdown);
        Instant syncedAt = Instant.now();
        var moves = transaction.execute(status -> {
            boolean previouslySynced = connections.lockForSync(user.id(), connectionId);
            var applied = documents.publishSnapshot(connectionId, branch.commitSha(), markdown, syncedAt, contentMoves, previouslySynced);
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

    /**
     * Vanished documents that carry user data, paired by content with paths that never had a document. Skipped when
     * there are more than MAX_CONTENT_MOVE_CANDIDATES. A version GitHub no longer has (rewritten history) or cannot be
     * read as a note excludes only that candidate; any other failure fails the sync.
     */
    private List<Documents.Move> contentMoves(AppUser user, RepositoryConnections.Connection connection, List<Documents.SourceFile> files) {
        var existing = documents.existing(connection.id());
        var exact = Documents.exactMoves(existing, files);
        var snapshotPaths = new HashSet<String>();
        for (var file : files) snapshotPaths.add(file.path());
        var knownPaths = new HashSet<String>();
        for (var row : existing) knownPaths.add(row.path());
        var claimedIds = new HashSet<Long>();
        var claimedPaths = new HashSet<String>();
        for (var move : exact) {
            claimedIds.add(move.documentId());
            claimedPaths.add(move.toPath());
        }
        var withState = documents.withUserState(connection.id());
        var vanished = existing.stream().filter(row -> !row.deleted() && !snapshotPaths.contains(row.path())
            && !claimedIds.contains(row.id()) && withState.contains(row.id())).toList();
        var fresh = files.stream().filter(file -> !knownPaths.contains(file.path()) && !claimedPaths.contains(file.path())).toList();
        if (vanished.isEmpty() || fresh.isEmpty()) return List.of();
        if (vanished.size() + fresh.size() > MAX_CONTENT_MOVE_CANDIDATES) {
            LOG.info("Content moves not checked; connectionId={} vanishedWithUserData={} newPaths={} limit={}",
                connection.id(), vanished.size(), fresh.size(), MAX_CONTENT_MOVE_CANDIDATES);
            return List.of();
        }
        var oldVersions = new HashMap<Long, Set<String>>();
        for (var row : vanished) shingles(user, connection, row.path(), row.blobSha()).ifPresent(set -> oldVersions.put(row.id(), set));
        var newVersions = new HashMap<String, Set<String>>();
        for (var file : fresh) shingles(user, connection, file.path(), file.blobSha()).ifPresent(set -> newVersions.put(file.path(), set));
        var paths = new HashMap<Long, String>();
        for (var row : vanished) paths.put(row.id(), row.path());
        return ContentMoves.pairs(oldVersions, newVersions).entrySet().stream()
            .map(pair -> new Documents.Move(pair.getKey(), paths.get(pair.getKey()), pair.getValue())).toList();
    }

    private Optional<Set<String>> shingles(AppUser user, RepositoryConnections.Connection connection, String path, String blobSha) {
        try {
            return Optional.of(ContentMoves.shingles(noteVersions.render(user, connection.owner(), connection.name(), path, blobSha).note().blocks()));
        } catch (ApiFailure failure) {
            if (!failure.code.equals("SOURCE_NOT_FOUND") && !failure.code.equals("UNSUPPORTED_CONTENT")) throw failure;
            LOG.info("Content move candidate skipped; connectionId={} path={} blobSha={} code={}", connection.id(), path, blobSha, failure.code);
            return Optional.empty();
        }
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
