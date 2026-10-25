package com.reporead.sync;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.auth.AppUser;
import com.reporead.auth.GitHubTokenLifecycle;
import com.reporead.document.Attachments;
import com.reporead.document.DocumentVersions;
import com.reporead.document.Documents;
import com.reporead.document.MarkdownRenderer;
import com.reporead.github.GitHubApi;
import com.reporead.repository.RepositoryConnections;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciles a connection's Markdown documents with its default branch. Ceiling: 2 GitHub requests (branch, recursive
 * tree) plus at most 128 blob reads to recognize notes moved and edited. All validation happens before the single
 * publishing transaction; any failure leaves the previous snapshot and checkpoint untouched.
 */
@Service
public class RepositorySnapshotRefresh {
    private static final Logger LOG = LoggerFactory.getLogger(RepositorySnapshotRefresh.class);
    static final int MAX_DOCUMENTS = 5_000;
    static final String SNAPSHOT_REFRESHES = "reporead.github.syncs";
    static final String DOCUMENTS_SYNCED = "reporead.documents.synced";
    /**
     * Vanished documents plus new paths compared by content in one snapshot refresh; more is skipped (each blob is one
     * request). The largest reorganization in the measured notes repository had 99 (ADR-07).
     */
    static final int MAX_CONTENT_MOVE_CANDIDATES = 128;
    /**
     * Blob reads in flight at once. 128 / 16 is at most 8 rounds, the worst case of the 8 sequential reads before; the
     * 99 real candidates took 2.3–2.8 s at 16 against 36–37 s one at a time (ADR-07).
     */
    static final int CONCURRENT_BLOB_READS = 16;

    private final GitHubApi github;
    private final GitHubTokenLifecycle credentials;
    private final RepositoryConnections connections;
    private final Documents documents;
    private final TransactionTemplate transaction;
    private final DocumentVersions documentVersions;
    private final MeterRegistry meters;
    private final Attachments attachments;
    private final Object contentMoveReadAdmission = new Object();

    public RepositorySnapshotRefresh(
            GitHubApi github,
            GitHubTokenLifecycle credentials,
            RepositoryConnections connections,
            Documents documents,
            TransactionTemplate transaction,
            DocumentVersions documentVersions,
            MeterRegistry meters,
            Attachments attachments) {
        this.github = github;
        this.credentials = credentials;
        this.connections = connections;
        this.documents = documents;
        this.transaction = transaction;
        this.documentVersions = documentVersions;
        this.meters = meters;
        this.attachments = attachments;
    }

    /**
     * Counts every snapshot refresh by outcome and failure code, and the documents published by each successful one.
     * Metric identifiers stay stable for existing dashboards.
     */
    public RepositorySyncResult refresh(AppUser user, long connectionId) {
        RepositorySyncResult refreshResult;
        try {
            refreshResult = fetchAndPublishSnapshot(user, connectionId);
        } catch (RuntimeException failure) {
            meters.counter(
                            SNAPSHOT_REFRESHES,
                            "outcome",
                            "failure",
                            "code",
                            failure instanceof ApiFailure api ? api.code.name() : "UNEXPECTED")
                    .increment();
            throw failure;
        }
        meters.counter(SNAPSHOT_REFRESHES, "outcome", "success", "code", "NONE").increment();
        meters.counter(DOCUMENTS_SYNCED).increment(refreshResult.documentCount());
        return refreshResult;
    }

    private RepositorySyncResult fetchAndPublishSnapshot(AppUser user, long connectionId) {
        var connection = connections.find(user.id(), connectionId).orElseThrow(ApiFailure::notFound);
        String githubToken = credentials.githubUserToken(user.githubUserId());
        var branchSnapshot =
                github.branch(githubToken, connection.owner(), connection.name(), connection.defaultBranch());
        var repositoryTreeEntries =
                github.completeTree(githubToken, connection.owner(), connection.name(), branchSnapshot.treeSha());
        var githubSnapshotFiles = markdownDocuments(repositoryTreeEntries);
        var imagePaths = imageFiles(repositoryTreeEntries);
        var detectedMoves = contentMoves(user, connection, githubSnapshotFiles);
        Instant syncedAt = Instant.now();
        var publishedMoves = transaction.execute(transactionStatus -> {
            var lockedConnection = connections.lockForSync(user.id(), connectionId);
            // A reconnect changed the branch while this refresh fetched; its snapshot is no longer current.
            if (!lockedConnection.defaultBranch().equals(connection.defaultBranch())) {
                LOG.info(
                        "Snapshot refresh superseded; connectionId={} fetchedBranch={} defaultBranch={}",
                        connectionId,
                        connection.defaultBranch(),
                        lockedConnection.defaultBranch());
                throw new ApiFailure(
                        Code.SYNC_SUPERSEDED,
                        "The repository's default branch changed during this refresh; nothing was published.");
            }
            String publishedCommitSha = lockedConnection.commitSha();
            // Another refresh published while this one fetched; its snapshot may be newer, so this one must not replace
            // it.
            if (!Objects.equals(publishedCommitSha, connection.lastSyncedCommitSha())
                    && !branchSnapshot.commitSha().equals(publishedCommitSha)) {
                LOG.info(
                        "Snapshot refresh superseded; connectionId={} fetched={} startedFrom={} published={}",
                        connectionId,
                        branchSnapshot.commitSha(),
                        connection.lastSyncedCommitSha(),
                        publishedCommitSha);
                throw new ApiFailure(
                        Code.SYNC_SUPERSEDED,
                        "Another refresh of this repository published first; nothing was published.");
            }
            var appliedMoves = documents.publishSnapshot(
                    connectionId, githubSnapshotFiles, syncedAt, detectedMoves, publishedCommitSha != null);
            attachments.replaceImages(connectionId, imagePaths);
            connections.markSynced(connectionId, branchSnapshot.commitSha());
            return appliedMoves;
        });
        for (var move : publishedMoves) {
            LOG.info("Document moved; connectionId={} documentId={}", connectionId, move.documentId());
        }
        LOG.info(
                "Repository snapshot refreshed; userId={} connectionId={} commitSha={} documents={} moves={}",
                user.id(),
                connectionId,
                branchSnapshot.commitSha(),
                githubSnapshotFiles.size(),
                publishedMoves.size());
        return new RepositorySyncResult(connectionId, branchSnapshot.commitSha(), githubSnapshotFiles.size(), syncedAt);
    }

    /**
     * Vanished documents that carry user data, paired by content with paths that never had a document. Skipped when
     * there are more than MAX_CONTENT_MOVE_CANDIDATES. A version GitHub no longer has (rewritten history) or cannot be
     * read as a note excludes only that candidate; any other failure fails the refresh.
     */
    private List<Documents.Move> contentMoves(
            AppUser user, RepositoryConnections.Connection connection, List<Documents.SourceFile> githubSnapshotFiles) {
        var unmatchedSnapshot = Documents.unmatched(documents.existing(connection.id()), githubSnapshotFiles);
        var documentIdsWithUserState = documents.withUserState(connection.id());
        var vanishedDocuments = unmatchedSnapshot.vanished().stream()
                .filter(document -> documentIdsWithUserState.contains(document.id()))
                .toList();
        var freshSnapshotFiles = unmatchedSnapshot.fresh();
        if (vanishedDocuments.isEmpty() || freshSnapshotFiles.isEmpty()) return List.of();
        if (vanishedDocuments.size() + freshSnapshotFiles.size() > MAX_CONTENT_MOVE_CANDIDATES) {
            LOG.info(
                    "Content moves not checked; connectionId={} vanishedWithUserData={} newPaths={} limit={}",
                    connection.id(),
                    vanishedDocuments.size(),
                    freshSnapshotFiles.size(),
                    MAX_CONTENT_MOVE_CANDIDATES);
            return List.of();
        }
        var versions = new ArrayList<Documents.SourceFile>();
        for (var document : vanishedDocuments)
            versions.add(new Documents.SourceFile(document.path(), document.blobSha()));
        versions.addAll(freshSnapshotFiles);
        var shinglesByVersion = shingles(user, connection, versions);
        var oldShinglesByDocumentId = new HashMap<Long, Set<String>>();
        for (int i = 0; i < vanishedDocuments.size(); i++) {
            long documentId = vanishedDocuments.get(i).id();
            shinglesByVersion.get(i).ifPresent(shingles -> oldShinglesByDocumentId.put(documentId, shingles));
        }
        var newShinglesByPath = new HashMap<String, Set<String>>();
        for (int i = 0; i < freshSnapshotFiles.size(); i++) {
            String path = freshSnapshotFiles.get(i).path();
            shinglesByVersion
                    .get(vanishedDocuments.size() + i)
                    .ifPresent(shingles -> newShinglesByPath.put(path, shingles));
        }
        var oldPathsByDocumentId = new HashMap<Long, String>();
        for (var document : vanishedDocuments) oldPathsByDocumentId.put(document.id(), document.path());
        return ContentMoves.pairs(oldShinglesByDocumentId, newShinglesByPath).entrySet().stream()
                .map(pair ->
                        new Documents.Move(pair.getKey(), oldPathsByDocumentId.get(pair.getKey()), pair.getValue()))
                .toList();
    }

    /**
     * Each version's shingles, in the order given, read in sets of CONCURRENT_BLOB_READS. One RepositorySnapshotRefresh
     * owns the admission boundary, so overlapping refreshes cannot multiply that process ceiling. A failed set fails
     * the refresh without admitting a later set.
     */
    private List<Optional<Set<String>>> shingles(
            AppUser user, RepositoryConnections.Connection connection, List<Documents.SourceFile> versions) {
        // Log lines from the readers carry the sync request's id; the pool's threads end with this sync.
        Map<String, String> requestContext = MDC.getCopyOfContextMap();
        synchronized (contentMoveReadAdmission) {
            var shinglesByVersion = new ArrayList<Optional<Set<String>>>(versions.size());
            try (var executor = Executors.newFixedThreadPool(CONCURRENT_BLOB_READS)) {
                for (int start = 0; start < versions.size(); start += CONCURRENT_BLOB_READS) {
                    var completions = new ExecutorCompletionService<Optional<Set<String>>>(executor);
                    var reads = new ArrayList<Future<Optional<Set<String>>>>();
                    for (var version :
                            versions.subList(start, Math.min(start + CONCURRENT_BLOB_READS, versions.size()))) {
                        reads.add(completions.submit(() -> {
                            if (requestContext != null) MDC.setContextMap(requestContext);
                            return shingles(user, connection, version.path(), version.blobSha());
                        }));
                    }
                    try {
                        for (int i = 0; i < reads.size(); i++)
                            completions.take().get();
                    } catch (ExecutionException failure) {
                        executor.shutdownNow();
                        if (failure.getCause() instanceof Error error) throw error;
                        if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException(
                                "Content move read failed; connectionId=" + connection.id(), failure.getCause());
                    } catch (InterruptedException interrupted) {
                        executor.shutdownNow();
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Interrupted while comparing content moves; connectionId=" + connection.id(),
                                interrupted);
                    }
                    reads.stream().map(Future::resultNow).forEach(shinglesByVersion::add);
                }
            }
            return List.copyOf(shinglesByVersion);
        }
    }

    private Optional<Set<String>> shingles(
            AppUser user, RepositoryConnections.Connection connection, String path, String blobSha) {
        try {
            return Optional.of(ContentMoves.shingles(documentVersions
                    .render(user, connection.owner(), connection.name(), path, blobSha)
                    .document()
                    .blocks()));
        } catch (ApiFailure failure) {
            if (!DocumentVersions.versionUnavailable(failure)) throw failure;
            LOG.info(
                    "Content move candidate skipped; connectionId={} blobSha={} code={}",
                    connection.id(),
                    blobSha,
                    failure.code);
            return Optional.empty();
        }
    }

    /** Regular-file blobs the reader displays as images, for resolving Obsidian embeds by name. */
    static List<String> imageFiles(List<GitHubApi.TreeEntry> repositoryTreeEntries) {
        return repositoryTreeEntries.stream()
                .filter(entry -> regularFile(entry) && MarkdownRenderer.isImagePath(entry.path()))
                .map(GitHubApi.TreeEntry::path)
                .toList();
    }

    /** Regular-file blobs ending in .md (any case). Symlinks, submodules, and other files are not documents. */
    static List<Documents.SourceFile> markdownDocuments(List<GitHubApi.TreeEntry> repositoryTreeEntries) {
        var snapshotMarkdownFiles = new ArrayList<Documents.SourceFile>();
        var seenPaths = new HashSet<String>();
        for (var entry : repositoryTreeEntries) {
            if (!regularFile(entry) || !entry.path().toLowerCase(Locale.ROOT).endsWith(".md")) continue;
            if (!seenPaths.add(entry.path())) {
                throw new ApiFailure(Code.GITHUB_INVALID_RESPONSE, "GitHub tree listed one path twice.");
            }
            if (snapshotMarkdownFiles.size() == MAX_DOCUMENTS) {
                throw new ApiFailure(
                        Code.DOCUMENT_LIMIT,
                        "This repository has more than "
                                + MAX_DOCUMENTS
                                + " Markdown files; no documents were changed.");
            }
            snapshotMarkdownFiles.add(new Documents.SourceFile(entry.path(), entry.sha()));
        }
        return List.copyOf(snapshotMarkdownFiles);
    }

    /** A plain or executable file: symlinks (120000) and submodules (commits) are neither notes nor images. */
    private static boolean regularFile(GitHubApi.TreeEntry entry) {
        return entry.type().equals("blob")
                && (entry.mode().equals("100644") || entry.mode().equals("100755"));
    }
}
