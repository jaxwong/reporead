package com.reporead.sync;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.auth.AppUser;
import com.reporead.auth.GitHubTokenLifecycle;
import com.reporead.document.Attachments;
import com.reporead.document.Documents;
import com.reporead.document.MarkdownRenderer;
import com.reporead.document.NoteVersions;
import com.reporead.github.GitHubApi;
import com.reporead.repository.RepositoryConnections;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciles a connection's Markdown documents with its default branch. Ceiling: 2 GitHub requests (branch, recursive
 * tree) plus at most 8 blob reads to recognize notes moved and edited. All validation happens before the single
 * publishing transaction; any failure leaves the previous snapshot and checkpoint untouched.
 */
@Service
public class RepositorySync {
    private static final Logger LOG = LoggerFactory.getLogger(RepositorySync.class);
    static final int MAX_DOCUMENTS = 5_000;
    static final String SYNCS = "reporead.github.syncs";
    static final String DOCUMENTS_SYNCED = "reporead.documents.synced";
    /**
     * Vanished documents plus new paths compared by content in one sync; more is skipped (each blob is one request).
     */
    static final int MAX_CONTENT_MOVE_CANDIDATES = 8;

    private final GitHubApi github;
    private final GitHubTokenLifecycle credentials;
    private final RepositoryConnections connections;
    private final Documents documents;
    private final TransactionTemplate transaction;
    private final NoteVersions noteVersions;
    private final MeterRegistry meters;
    private final Attachments attachments;

    public RepositorySync(
            GitHubApi github,
            GitHubTokenLifecycle credentials,
            RepositoryConnections connections,
            Documents documents,
            TransactionTemplate transaction,
            NoteVersions noteVersions,
            MeterRegistry meters,
            Attachments attachments) {
        this.github = github;
        this.credentials = credentials;
        this.connections = connections;
        this.documents = documents;
        this.transaction = transaction;
        this.noteVersions = noteVersions;
        this.meters = meters;
        this.attachments = attachments;
    }

    /**
     * Counts every sync in reporead.github.syncs by outcome and failure code, and the documents of each successful one
     * in reporead.documents.synced.
     */
    public RepositorySyncResult sync(AppUser user, long connectionId) {
        RepositorySyncResult syncResult;
        try {
            syncResult = fetchAndPublishSnapshot(user, connectionId);
        } catch (RuntimeException failure) {
            meters.counter(
                            SYNCS,
                            "outcome",
                            "failure",
                            "code",
                            failure instanceof ApiFailure api ? api.code.name() : "UNEXPECTED")
                    .increment();
            throw failure;
        }
        meters.counter(SYNCS, "outcome", "success", "code", "NONE").increment();
        meters.counter(DOCUMENTS_SYNCED).increment(syncResult.documentCount());
        return syncResult;
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
            // A reconnect changed the branch while this sync fetched; its snapshot is of a branch no longer synced.
            if (!lockedConnection.defaultBranch().equals(connection.defaultBranch())) {
                LOG.info(
                        "Sync superseded; connectionId={} fetchedBranch={} defaultBranch={}",
                        connectionId,
                        connection.defaultBranch(),
                        lockedConnection.defaultBranch());
                throw new ApiFailure(
                        Code.SYNC_SUPERSEDED,
                        "The repository's default branch changed during this sync; nothing was published.");
            }
            String publishedCommitSha = lockedConnection.commitSha();
            // Another sync published while this one fetched; its snapshot may be newer, so this one must not replace
            // it.
            if (!Objects.equals(publishedCommitSha, connection.lastSyncedCommitSha())
                    && !branchSnapshot.commitSha().equals(publishedCommitSha)) {
                LOG.info(
                        "Sync superseded; connectionId={} fetched={} startedFrom={} published={}",
                        connectionId,
                        branchSnapshot.commitSha(),
                        connection.lastSyncedCommitSha(),
                        publishedCommitSha);
                throw new ApiFailure(
                        Code.SYNC_SUPERSEDED,
                        "Another sync of this repository published first; nothing was published.");
            }
            var appliedMoves = documents.publishSnapshot(
                    connectionId, githubSnapshotFiles, syncedAt, detectedMoves, publishedCommitSha != null);
            attachments.replace(connectionId, imagePaths);
            connections.markSynced(connectionId, branchSnapshot.commitSha());
            return appliedMoves;
        });
        for (var move : publishedMoves) {
            LOG.info("Document moved; connectionId={} documentId={}", connectionId, move.documentId());
        }
        LOG.info(
                "Repository synced; userId={} connectionId={} commitSha={} documents={} moves={}",
                user.id(),
                connectionId,
                branchSnapshot.commitSha(),
                githubSnapshotFiles.size(),
                publishedMoves.size());
        return new RepositorySyncResult(
                connectionId, branchSnapshot.commitSha(), githubSnapshotFiles.size(), syncedAt);
    }

    /**
     * Vanished documents that carry user data, paired by content with paths that never had a document. Skipped when
     * there are more than MAX_CONTENT_MOVE_CANDIDATES. A version GitHub no longer has (rewritten history) or cannot be
     * read as a note excludes only that candidate; any other failure fails the sync.
     */
    private List<Documents.Move> contentMoves(
            AppUser user,
            RepositoryConnections.Connection connection,
            List<Documents.SourceFile> githubSnapshotFiles) {
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
        var oldShinglesByDocumentId = new HashMap<Long, Set<String>>();
        for (var document : vanishedDocuments) {
            shingles(user, connection, document.path(), document.blobSha())
                    .ifPresent(shingles -> oldShinglesByDocumentId.put(document.id(), shingles));
        }
        var newShinglesByPath = new HashMap<String, Set<String>>();
        for (var file : freshSnapshotFiles) {
            shingles(user, connection, file.path(), file.blobSha())
                    .ifPresent(shingles -> newShinglesByPath.put(file.path(), shingles));
        }
        var oldPathsByDocumentId = new HashMap<Long, String>();
        for (var document : vanishedDocuments) oldPathsByDocumentId.put(document.id(), document.path());
        return ContentMoves.pairs(oldShinglesByDocumentId, newShinglesByPath).entrySet().stream()
                .map(pair ->
                        new Documents.Move(pair.getKey(), oldPathsByDocumentId.get(pair.getKey()), pair.getValue()))
                .toList();
    }

    private Optional<Set<String>> shingles(
            AppUser user, RepositoryConnections.Connection connection, String path, String blobSha) {
        try {
            return Optional.of(ContentMoves.shingles(noteVersions
                    .render(user, connection.owner(), connection.name(), path, blobSha)
                    .note()
                    .blocks()));
        } catch (ApiFailure failure) {
            if (!NoteVersions.versionUnavailable(failure)) throw failure;
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
                        "This repository has more than 5000 Markdown files; no documents were changed.");
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
